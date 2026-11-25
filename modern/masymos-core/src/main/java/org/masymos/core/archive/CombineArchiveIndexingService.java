package org.masymos.core.archive;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.masymos.core.archive.CombineArchiveFormat.ArchiveEntryKind;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.core.extract.IndexedModel;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.core.extract.SedmlModelIndexingService;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.spi.ExtractedModelGraphRepository;

/**
 * Indexes the model and simulation description entries of a single COMBINE archive (OMEX).
 *
 * <p>Entries are <em>not</em> extracted to a temporary directory. Each entry is addressed by its
 * stable archive URI, {@code <archive-uri>!/<entry-path>}, which the content loader can read directly
 * from the zip. That keeps entry identities valid after the run and lets relative SED-ML references
 * inside the archive resolve to sibling entries.</p>
 *
 * <p>After indexing, the archive is recorded as its own node above the document nodes, so the bundle
 * stays visible in the graph: {@code (:CombineArchive)-[:HAS_DOCUMENT]->(:Document)}.</p>
 *
 * <p>Each entry is indexed with a provenance label derived from its own bytes
 * ({@link ContentDigest#versionLabel}), because an OMEX manifest declares no version label of an
 * entry - only its specification version, which the parser reads from the content (SBML-1).</p>
 *
 * <p>Entries that cannot be indexed are never fatal: they are reported in
 * {@link CombineArchiveIndexingResult#skipped()} with a reason instead.</p>
 */
public final class CombineArchiveIndexingService {
    /**
     * How long one outbound archive fetch may take in total. An archive is a document read in one
     * request, so thirty seconds is generous for a 64 MiB body; without a bound a server that never
     * answers holds the indexing thread for as long as the socket lives.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** How long the connection may take to establish, which is the failure a dead host produces. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final List<ArchiveEntryKind> INDEXED_KINDS =
            List.of(ArchiveEntryKind.SBML, ArchiveEntryKind.CELLML, ArchiveEntryKind.SEDML);

    private final SourceIndexer sbmlIndexingService;
    private final SourceIndexer cellmlIndexingService;
    private final SourceIndexer sedmlIndexingService;
    private final CombineArchiveReader reader;
    private final ModelSourceDiscovery discovery = new ModelSourceDiscovery();
    private final ExtractedModelGraphRepository graphRepository;
    private final Duration requestTimeout;
    private final Duration connectTimeout;
    private HttpClient httpClient;

    /**
     * Indexes the model files of one kind. A functional interface keeps the archive pipeline testable
     * without constructing the real content loaders.
     */
    @FunctionalInterface
    public interface SourceIndexer {
        List<IndexedModel> indexSources(List<ModelSource> sources) throws IOException;
    }

    public CombineArchiveIndexingService(
            SbmlModelIndexingService sbmlModelIndexingService,
            CellmlModelIndexingService cellmlModelIndexingService,
            SedmlModelIndexingService sedmlModelIndexingService,
            ExtractedModelGraphRepository graphRepository) {
        this(sbmlModelIndexingService::indexSources, cellmlModelIndexingService::indexSources,
                sedmlModelIndexingService::indexSources, new CombineArchiveReader(), graphRepository);
    }

    public CombineArchiveIndexingService(
            SourceIndexer sbmlIndexingService,
            SourceIndexer cellmlIndexingService,
            SourceIndexer sedmlIndexingService,
            CombineArchiveReader reader) {
        this(sbmlIndexingService, cellmlIndexingService, sedmlIndexingService, reader, null);
    }

    public CombineArchiveIndexingService(
            SourceIndexer sbmlIndexingService,
            SourceIndexer cellmlIndexingService,
            SourceIndexer sedmlIndexingService,
            CombineArchiveReader reader,
            ExtractedModelGraphRepository graphRepository) {
        this(sbmlIndexingService, cellmlIndexingService, sedmlIndexingService, reader, graphRepository,
                DEFAULT_REQUEST_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * The same service with the outbound-read bounds overridden. Package-private on purpose: the
     * shipped timeouts are the only ones production reaches, and a test must not have to wait thirty
     * seconds to prove that a read is bounded.
     */
    CombineArchiveIndexingService(
            SourceIndexer sbmlIndexingService,
            SourceIndexer cellmlIndexingService,
            SourceIndexer sedmlIndexingService,
            CombineArchiveReader reader,
            ExtractedModelGraphRepository graphRepository,
            Duration requestTimeout,
            Duration connectTimeout) {
        this.sbmlIndexingService = sbmlIndexingService;
        this.cellmlIndexingService = cellmlIndexingService;
        this.sedmlIndexingService = sedmlIndexingService;
        this.reader = reader;
        this.graphRepository = graphRepository;
        this.requestTimeout = requestTimeout;
        this.connectTimeout = connectTimeout;
    }

    /**
     * Reads the archive at {@code archiveUri}, indexes its model and simulation description entries
     * and reports the entries that were not indexed.
     *
     * @throws CombineArchiveException if the archive cannot be read or an indexing service fails; the
     *     message of a failed run names the models the kinds that already succeeded wrote, because
     *     those writes are not rolled back
     */
    public CombineArchiveIndexingResult index(URI archiveUri) {
        if (archiveUri == null) {
            throw new CombineArchiveException("archive uri must not be null");
        }
        var archive = readArchive(archiveUri);
        var skipped = new ArrayList<String>();
        var sourcesByKind = new EnumMap<ArchiveEntryKind, List<ModelSource>>(ArchiveEntryKind.class);
        for (var kind : INDEXED_KINDS) {
            sourcesByKind.put(kind, new ArrayList<>());
        }

        var entryUris = new ArrayList<URI>();
        for (var entry : archive.entries()) {
            var kind = entry.format().kind();
            if (!INDEXED_KINDS.contains(kind)) {
                // The raw format URI is part of the reason: without it an operator cannot tell a
                // manifest typo (sbmlx) from a deliberate non-model entry (a results PDF).
                skipped.add(entry.location() + ": unsupported entry kind " + kind
                        + " (format " + entry.format().formatUri() + ")");
                continue;
            }
            var normalisedPath = CombineArchiveUri.normaliseEntryPath(entry.location());
            if (normalisedPath.isEmpty()) {
                skipped.add(entry.location() + ": entry path escapes its archive or names no entry");
                continue;
            }
            var content = archive.content(entry.location());
            if (content.isEmpty()) {
                // A manifest may spell a member the way the archive stores it, or URI-encoded; both
                // spellings name the same member, so the second lookup is the normalised one.
                content = archive.content(normalisedPath.get());
            }
            if (content.isEmpty()) {
                skipped.add(entry.location() + ": archive has no content for this entry");
                continue;
            }
            var modelType = modelTypeOf(kind);
            var entryUri = CombineArchiveUri.of(archiveUri, normalisedPath.get());
            sourcesByKind.get(kind).add(new ModelSource(new ModelReference(
                    discovery.archiveEntryFileId(entryUri),
                    entryUri,
                    modelType,
                    ContentDigest.versionLabel(content.get()))));
            entryUris.add(entryUri);
        }

        if (entryUris.isEmpty()) {
            return new CombineArchiveIndexingResult(List.of(), 0, List.copyOf(skipped));
        }

        var models = new ArrayList<IndexedModel>();
        indexKind(ArchiveEntryKind.SBML, sourcesByKind.get(ArchiveEntryKind.SBML), models);
        indexKind(ArchiveEntryKind.CELLML, sourcesByKind.get(ArchiveEntryKind.CELLML), models);
        indexKind(ArchiveEntryKind.SEDML, sourcesByKind.get(ArchiveEntryKind.SEDML), models);
        // Only a complete run links the archive node: the writes of a failed run are not rolled back,
        // but a link would present the bundle as fully indexed while some entries are missing.
        linkArchive(archiveUri, entryUris);
        return new CombineArchiveIndexingResult(List.copyOf(models), entryUris.size(), List.copyOf(skipped));
    }

    /**
     * The model type of an indexed entry. Only the three kinds in {@link #INDEXED_KINDS} reach this
     * point; anything else was skipped before.
     */
    private static ModelType modelTypeOf(ArchiveEntryKind kind) {
        return switch (kind) {
            case SBML -> ModelType.SBML;
            case CELLML -> ModelType.CELLML;
            case SEDML -> ModelType.SEDML;
            default -> throw new IllegalArgumentException("not an indexed entry kind: " + kind);
        };
    }

    private void linkArchive(URI archiveUri, List<URI> entryUris) {
        if (graphRepository == null) {
            return;
        }
        graphRepository.linkArchive(archiveUri, entryUris);
    }

    private CombineArchive readArchive(URI archiveUri) {
        var scheme = archiveUri.getScheme();
        try (var stream = openStream(archiveUri, scheme)) {
            return reader.read(stream);
        } catch (IOException exception) {
            throw new CombineArchiveException("failed to read archive " + archiveUri + ": " + exception.getMessage(),
                    exception);
        }
    }

    private InputStream openStream(URI archiveUri, String scheme) throws IOException {
        if ("file".equalsIgnoreCase(scheme) || scheme == null) {
            return Files.newInputStream(scheme == null ? Path.of(archiveUri.toString()) : Path.of(archiveUri));
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            var request = HttpRequest.newBuilder(archiveUri).timeout(requestTimeout).GET().build();
            try {
                return httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading " + archiveUri, exception);
            } catch (java.net.http.HttpTimeoutException exception) {
                // The URI is part of the message: a run over many archives must say which one it gave
                // up on, and how long it was willing to wait.
                throw new IOException("timed out after " + requestTimeout + " downloading " + archiveUri, exception);
            }
        }
        throw new CombineArchiveException("unsupported archive uri scheme: " + scheme);
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
        return httpClient;
    }

    /**
     * Indexes one kind's sources into {@code written}, which already holds every model the earlier
     * kinds wrote. A failure is refused naming those, because their writes are already in the store
     * and are not rolled back: without the names the refusal reads as if nothing had happened.
     */
    private void indexKind(ArchiveEntryKind kind, List<ModelSource> sources, List<IndexedModel> written) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        try {
            written.addAll(switch (kind) {
                case SBML -> sbmlIndexingService.indexSources(sources);
                case CELLML -> cellmlIndexingService.indexSources(sources);
                case SEDML -> sedmlIndexingService.indexSources(sources);
                default -> List.of();
            });
        } catch (IOException exception) {
            throw new CombineArchiveException(
                    "failed to index " + kind + " entries of the archive: " + exception.getMessage()
                            + alreadyWritten(written), exception);
        }
    }

    /**
     * Names the models an earlier kind already wrote, for the refusal of a failed run. The source's
     * {@code fileId} is the fallback for a repository that answers no identifier, so the message names
     * something a reader can find either way.
     */
    private static String alreadyWritten(List<IndexedModel> written) {
        if (written.isEmpty()) {
            return "; no model had been written yet";
        }
        var fileIds = written.stream()
                .map(model -> model.identifier() == null
                        ? model.source().reference().fileId()
                        : model.identifier().fileId())
                .toList();
        return "; " + fileIds.size() + (fileIds.size() == 1 ? " model was" : " models were")
                + " already written and not rolled back: " + fileIds;
    }

    /**
     * The outcome of indexing one archive.
     *
     * @param models all indexed models, in SBML, CellML, SEDML order
     * @param indexedEntries how many archive entries were handed to an indexing service
     * @param skipped archive entries that were not indexed, each as {@code location: reason}
     */
    public record CombineArchiveIndexingResult(
            List<IndexedModel> models,
            int indexedEntries,
            List<String> skipped) {
    }
}

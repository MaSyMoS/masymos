package org.masymos.core.archive;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final List<ArchiveEntryKind> INDEXED_KINDS =
            List.of(ArchiveEntryKind.SBML, ArchiveEntryKind.CELLML, ArchiveEntryKind.SEDML);

    private final SourceIndexer sbmlIndexingService;
    private final SourceIndexer cellmlIndexingService;
    private final SourceIndexer sedmlIndexingService;
    private final CombineArchiveReader reader;
    private final ModelSourceDiscovery discovery = new ModelSourceDiscovery();
    private final ExtractedModelGraphRepository graphRepository;
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
        this.sbmlIndexingService = sbmlIndexingService;
        this.cellmlIndexingService = cellmlIndexingService;
        this.sedmlIndexingService = sedmlIndexingService;
        this.reader = reader;
        this.graphRepository = graphRepository;
    }

    /**
     * Reads the archive at {@code archiveUri}, indexes its model and simulation description entries
     * and reports the entries that were not indexed.
     *
     * @throws CombineArchiveException if the archive cannot be read or an indexing service fails
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
                skipped.add(entry.location() + ": unsupported entry kind " + kind);
                continue;
            }
            var content = archive.content(entry.location());
            if (content.isEmpty()) {
                skipped.add(entry.location() + ": archive has no content for this entry");
                continue;
            }
            var modelType = modelTypeOf(kind);
            var entryUri = CombineArchiveUri.of(archiveUri, entry.location());
            sourcesByKind.get(kind).add(new ModelSource(new ModelReference(
                    discovery.deriveFileId(entryUri, modelType),
                    entryUri,
                    modelType,
                    ContentDigest.versionLabel(content.get()))));
            entryUris.add(entryUri);
        }

        if (entryUris.isEmpty()) {
            return new CombineArchiveIndexingResult(List.of(), 0, List.copyOf(skipped));
        }

        var models = new ArrayList<IndexedModel>();
        models.addAll(indexKind(ArchiveEntryKind.SBML, sourcesByKind.get(ArchiveEntryKind.SBML)));
        models.addAll(indexKind(ArchiveEntryKind.CELLML, sourcesByKind.get(ArchiveEntryKind.CELLML)));
        models.addAll(indexKind(ArchiveEntryKind.SEDML, sourcesByKind.get(ArchiveEntryKind.SEDML)));
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
            var request = HttpRequest.newBuilder(archiveUri).GET().build();
            try {
                return httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while downloading " + archiveUri, exception);
            }
        }
        throw new CombineArchiveException("unsupported archive uri scheme: " + scheme);
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        }
        return httpClient;
    }

    private List<IndexedModel> indexKind(ArchiveEntryKind kind, List<ModelSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        try {
            return switch (kind) {
                case SBML -> sbmlIndexingService.indexSources(sources);
                case CELLML -> cellmlIndexingService.indexSources(sources);
                case SEDML -> sedmlIndexingService.indexSources(sources);
                default -> List.of();
            };
        } catch (IOException exception) {
            throw new CombineArchiveException(
                    "failed to index " + kind + " entries of the archive: " + exception.getMessage(), exception);
        }
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

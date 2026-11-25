package org.masymos.neo4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.masymos.core.archive.CombineArchiveReader;
import org.masymos.core.corpus.CorpusManifest;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.RemotePmrCellmlExternalCmetaResolver;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.extract.SedmlModelParser;
import org.masymos.core.extract.SourceDocument;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * Reads one manifest row's documents into parsed models - the corpus lane's own dispatch.
 *
 * <p>Test-only on purpose: the lane ({@link CorpusCoverageContainersIT}) and the report's unit tests
 * read documents the same way, so a change to the dispatch cannot pass the one that is cheap to run
 * while breaking the one that is not. A row is the file itself, or one member per parsable entry for
 * an archive (CORP-12).
 *
 * <p>A {@code sidecar} row is a cache file, not a document ({@link CorpusManifest.Role#SIDECAR}): it
 * is the {@code @@cmeta} sibling a harvested CellML row's model reads, so parsing it would count one
 * model twice (CORP-10).
 */
final class CorpusDocuments {

    private CorpusDocuments() {}

    /**
     * What the storage path keeps of one parsed document ({@code CORP-16}): its size before truncation,
     * and whether the copy is cut. Both come from the same {@link SourceDocument#from(byte[], int)} call
     * the indexing services make ({@code STO-2}) at the default ceiling, so the report measures the rule
     * the write path applies instead of re-deriving it.
     *
     * <p>Measured, not written. Writing these copies in the lane was tried and does not fit: the test
     * container's `dbms.memory.transaction.total.max` is 344.4 MiB, ~45 MB of stored text had already
     * charged ~330 MiB of it (a ~7x amplification), and an 11.8 MB archive died on the next 2 MiB
     * (measured 2026-11-25). The report needs the facts above, not 151 MB of `xmlDoc` in a throwaway
     * graph, and the production path writes one document per request under the documented profile.
     */
    record ReadDocument(ParsedModel model, SourceDocument source) {}

    /**
     * Every parsable document a manifest row declares, from the corpus cache under {@code corpusRoot}.
     *
     * <p>A file row's source URI is the cached file's own {@code file:} URI rather than a synthetic
     * {@code urn:} one, because the document really is that file: the shipped {@link
     * RemotePmrCellmlExternalCmetaResolver} reads a {@code file:} source's sibling {@code @@cmeta}, and
     * a {@code urn:} source would therefore never see a harvested sidecar (CORP-10).
     */
    static List<ReadDocument> of(CorpusManifest.Row row, Path corpusRoot) throws IOException {
        if (row.role() == CorpusManifest.Role.SIDECAR) {
            // The lane skips sidecar rows before calling this, so reaching it is a dispatch bug.
            throw new IllegalArgumentException(
                    "manifest row " + row.file() + " is a sidecar, not a document; skip it before parsing");
        }
        var file = corpusRoot.resolve("files").resolve(row.file());
        var bytes = Files.readAllBytes(file);
        if (!"OMEX".equals(row.format())) {
            return List.of(parse(ModelType.valueOf(row.format()), row.id(), file.toUri(), bytes));
        }
        return ofArchive(row.id(), bytes);
    }

    /** Every parsable member of one archive, in manifest order. */
    static List<ReadDocument> ofArchive(String id, byte[] bytes) {
        var archive = new CombineArchiveReader().read(new ByteArrayInputStream(bytes));
        var parsed = new ArrayList<ReadDocument>();
        for (var entry : archive.entries()) {
            var content = archive.content(entry.location());
            if (content.isEmpty()) {
                continue;
            }
            var type = switch (entry.format().kind()) {
                case SBML -> ModelType.SBML;
                case CELLML -> ModelType.CELLML;
                case SEDML -> ModelType.SEDML;
                default -> null;
            };
            if (type != null) {
                // A member keeps its own file id (`<archive id>!<location>`): the version dimension
                // counts documents, and two members sharing one id would count as one document.
                parsed.add(parse(type, id + "!" + entry.location(),
                        URI.create("urn:masymos:corpus:" + id + "/" + entry.location()), content.get()));
            }
        }
        return parsed;
    }

    private static ReadDocument parse(ModelType type, String id, URI sourceUri, byte[] bytes) {
        var reference = new ModelReference(id, sourceUri, type);
        var source = new ModelSource(reference);
        var content = new ByteArrayInputStream(bytes);
        var model = switch (type) {
            case SBML -> new SbmlModelParser().parse(source, content);
            // The shipped resolver, never a test double: the point is that the production read path is
            // what the corpus lane exercises (CORP-10, CML-3).
            case CELLML -> new CellmlModelParser(new RemotePmrCellmlExternalCmetaResolver()).parse(source, content);
            case SEDML -> new SedmlModelParser().parse(source, content);
            case OWL, P2M -> throw new IllegalArgumentException("unsupported corpus format " + type);
        };
        return new ReadDocument(model, SourceDocument.from(bytes, SourceDocument.DEFAULT_MAX_BYTES));
    }
}

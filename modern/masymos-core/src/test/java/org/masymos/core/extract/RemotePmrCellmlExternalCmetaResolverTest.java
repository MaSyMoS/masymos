package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class RemotePmrCellmlExternalCmetaResolverTest {
    private static final Path FIXTURES = Path.of("src", "test", "resources", "cellml-cmeta");
    private static final Path FIXTURE = FIXTURES.resolve("local-model.cellml");

    @Test
    void readsCmetaFromALocalFileUri() {
        var model = source(FIXTURE.toAbsolutePath().toUri());

        var resolved = new RemotePmrCellmlExternalCmetaResolver().resolve(model);

        assertEquals(1, resolved.size());
        var cmeta = resolved.getFirst();
        assertEquals("External cmeta from a local file", cmeta.title());
        assertEquals("Ada Lovelace", cmeta.modelAuthor());
        assertEquals("https://identifiers.org/pubmed/424242", cmeta.citationId());
    }

    @Test
    void readsKeywordsFromALocalFileUriAsTheRawNestedLists() {
        var model = source(FIXTURE.toAbsolutePath().toUri());

        var cmeta = new RemotePmrCellmlExternalCmetaResolver().resolve(model).getFirst();

        assertEquals(List.of(List.of("cardiac", "electrophysiology"), List.of("calcium_handling")), cmeta.keywords());
    }

    @Test
    void aMissingLocalCmetaDegradesToNoMetadata() {
        var model = source(Path.of("target", "test-work", "absent", "nothing.cellml").toAbsolutePath().toUri());

        assertEquals(List.of(), new RemotePmrCellmlExternalCmetaResolver().resolve(model));
    }

    @Test
    void aLocalCmetaThatIsNotJsonDegradesToNoMetadata() {
        var model = source(FIXTURES.resolve("not-json.cellml").toAbsolutePath().toUri());

        assertEquals(List.of(), new RemotePmrCellmlExternalCmetaResolver().resolve(model));
    }

    @Test
    void anUnsupportedSchemeIsStillRefused() {
        var model = source(URI.create("ftp://example.org/models/a.cellml"));

        assertEquals(List.of(), new RemotePmrCellmlExternalCmetaResolver().resolve(model));
    }

    @Test
    void theDefaultParserStaysOptOutOfExternalCmeta() {
        assertTrue(
                CellmlExternalCmetaResolver.NONE.resolve(source(FIXTURE.toAbsolutePath().toUri())).isEmpty(),
                "the default resolver must fetch nothing; external cmeta is opt-in");
    }

    private ModelSource source(URI uri) {
        return new ModelSource(new ModelReference("local-model.cellml", uri, ModelType.CELLML));
    }
}

package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.masymos.core.archive.CombineArchiveUri;
import org.masymos.core.model.ModelType;

class ModelSourceDiscoveryTest {
    private final ModelSourceDiscovery discovery = new ModelSourceDiscovery();

    private Path tempDir;

    @BeforeEach
    void createTestDirectory() throws Exception {
        tempDir = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
    }

    @Test
    void readsUrlsFromListFilesAndDerivesSbmlFileIdsLikeLegacyExtractor() throws Exception {
        Files.writeString(tempDir.resolve("models.list"), """
                https://example.org/download?model=BIOMD0000000001
                https://example.org/models/plain.xml
                """);

        var sources = discovery.fromListFiles(tempDir, ModelType.SBML);

        assertEquals(2, sources.size());
        assertEquals("BIOMD0000000001", sources.getFirst().reference().fileId());
        assertEquals("plain.xml", sources.get(1).reference().fileId());
        assertEquals(ModelType.SBML, sources.getFirst().reference().modelType());
    }

    @Test
    void discoversModelFilesRecursively() throws Exception {
        var nested = Files.createDirectories(tempDir.resolve("nested"));
        var sbmlFile = Files.writeString(nested.resolve("model.sbml"), "<sbml/>");
        Files.writeString(nested.resolve("notes.txt"), "ignore");

        var sources = discovery.fromModelFiles(tempDir, ModelType.SBML);

        assertEquals(1, sources.size());
        assertEquals("model.sbml", sources.getFirst().reference().fileId());
        assertEquals(sbmlFile.toUri(), sources.getFirst().reference().sourceUri());
    }

    @Test
    void derivesFileIdFromLastPathSegmentWhenNoQueryStyleIdentifierExists() {
        var fileId = discovery.deriveFileId(URI.create("https://example.org/models/model.xml"), ModelType.CELLML);

        assertEquals("model.xml", fileId);
    }

    /**
     * The {@code PAR-1} contract: two entries of one archive with the same file name are two documents,
     * so the identity carries the directory inside the archive - and only that directory, never the
     * archive's own path, because the archive is identified by its own document node.
     */
    @Test
    void twoArchiveEntriesWithTheSameFileNameGetDistinctFileIds() {
        var archive = URI.create("file:///d/a.omex");
        var a = discovery.deriveFileId(CombineArchiveUri.of(archive, "models/a/x.sbml"), ModelType.SBML);
        var b = discovery.deriveFileId(CombineArchiveUri.of(archive, "b/x.sbml"), ModelType.SBML);

        assertNotEquals(a, b);
        assertEquals("x.sbml@models/a", a);
        assertEquals("x.sbml@b", b);
        assertEquals("x.sbml", discovery.deriveFileId(CombineArchiveUri.of(archive, "x.sbml"), ModelType.SBML));

        // The archive's own path is not part of the identity: a copied archive names the same entries.
        assertEquals(a, discovery.deriveFileId(
                CombineArchiveUri.of(URI.create("file:///elsewhere/b.omex"), "models/a/x.sbml"), ModelType.SBML));
        // Case is identity, and the encoded entry path is what the identity carries.
        assertEquals("A.sbml@Models",
                discovery.deriveFileId(CombineArchiveUri.of(archive, "Models/A.sbml"), ModelType.SBML));
        assertEquals("a%20b.sbml@models",
                discovery.deriveFileId(CombineArchiveUri.of(archive, "models/a b.sbml"), ModelType.SBML));
    }

    @Test
    void aStandaloneFileKeepsItsBareFileName() {
        assertEquals("model.xml",
                discovery.deriveFileId(URI.create("https://example.org/models/model.xml"), ModelType.CELLML));
        assertEquals("m.sbml", discovery.deriveFileId(URI.create("file:///d/models/m.sbml"), ModelType.SBML));
        // The legacy query-style branch is untouched for a URI that is not an archive entry.
        assertEquals("BIOMD0000000001",
                discovery.deriveFileId(URI.create("https://example.org/download?model=BIOMD0000000001"),
                        ModelType.SBML));
    }
}

package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}

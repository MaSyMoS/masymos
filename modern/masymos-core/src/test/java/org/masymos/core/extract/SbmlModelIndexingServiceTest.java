package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.spi.ExtractedModelGraphRepository;

class SbmlModelIndexingServiceTest {
    private static final String SBML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sbml xmlns="http://www.sbml.org/sbml/level3/version2/core" level="3" version="2">
              <model id="demo_model" name="Grüße">
                <listOfCompartments>
                  <compartment id="cell" constant="true"/>
                </listOfCompartments>
              </model>
            </sbml>
            """;

    Path tempDirectory;

    @BeforeEach
    void createTestDirectory() throws IOException {
        tempDirectory = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
    }

    @Test
    void discoversParsesMapsAndStoresSbmlModelFiles() throws IOException {
        var sbmlFile = tempDirectory.resolve("demo.sbml");
        Files.writeString(sbmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version2/core" level="3" version="2">
                  <model id="demo_model" name="Demo model">
                    <listOfCompartments>
                      <compartment id="cell" constant="true"/>
                    </listOfCompartments>
                  </model>
                </sbml>
                """, StandardCharsets.UTF_8);
        var repository = new RecordingGraphRepository();
        var service = new SbmlModelIndexingService(
                new ModelSourceDiscovery(),
                new UriModelSourceContentLoader(),
                new SbmlModelParser(),
                new ParsedModelGraphMapper(),
                repository
        );

        var indexedModels = service.indexModelFiles(tempDirectory);

        assertEquals(1, indexedModels.size());
        assertEquals("uid-1", indexedModels.getFirst().identifier().uid());
        assertEquals("demo.sbml", indexedModels.getFirst().identifier().fileId());
        assertEquals(1, repository.graphs.size());

        var graph = repository.graphs.getFirst();
        assertEquals("demo.sbml", graph.reference().fileId());
        assertEquals("demo_model", graph.nodes().get(1).properties().get("modelId"));
        assertEquals("Demo model", graph.nodes().get(1).properties().get("name"));
    }

    @Test
    void capturesTheIndexedDocumentSoItCanBeServedBackUnchanged() throws IOException {
        var repository = new RecordingGraphRepository();
        var service = service(repository, 4096);
        var sbmlFile = writeModel("demo.sbml", SBML);

        service.indexModelFiles(tempDirectory);

        var document = repository.graphs.getFirst().sourceDocument();
        assertNotNull(document);
        assertFalse(document.truncated());
        assertEquals(SBML, document.content());
        assertArrayEquals(Files.readAllBytes(sbmlFile), document.contentBytes());
    }

    @Test
    void flagsADocumentLargerThanTheStorageLimitInsteadOfDroppingItSilently() throws IOException {
        var repository = new RecordingGraphRepository();
        var service = service(repository, 16);
        var sbmlFile = writeModel("demo.sbml", SBML);

        service.indexModelFiles(tempDirectory);

        var document = repository.graphs.getFirst().sourceDocument();
        assertNotNull(document);
        assertTrue(document.truncated());
        assertEquals(Files.size(sbmlFile), document.originalBytes());
        assertEquals(16, document.contentBytes().length);
    }

    private Path writeModel(String name, String content) throws IOException {
        var file = tempDirectory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static SbmlModelIndexingService service(RecordingGraphRepository repository, int maxDocumentBytes) {
        return new SbmlModelIndexingService(
                new ModelSourceDiscovery(),
                new UriModelSourceContentLoader(),
                new SbmlModelParser(),
                new ParsedModelGraphMapper(),
                repository,
                maxDocumentBytes
        );
    }

    private static final class RecordingGraphRepository implements ExtractedModelGraphRepository {
        private final ArrayList<ExtractedModelGraph> graphs = new ArrayList<>();

        @Override
        public ModelIdentifier insert(ExtractedModelGraph graph) {
            graphs.add(graph);
            return new ModelIdentifier("uid-" + graphs.size(), graph.reference().fileId());
        }

        @Override
        public void linkArchive(URI archiveUri, List<URI> entrySourceUris) {
        }
    }
}

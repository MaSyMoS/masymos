package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

class SedmlModelIndexingServiceTest {
    Path tempDirectory;

    @BeforeEach
    void createTestDirectory() throws IOException {
        tempDirectory = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
    }

    @Test
    void discoversParsesMapsAndStoresSedmlFiles() throws IOException {
        var sedmlFile = tempDirectory.resolve("simulation.sedml");
        Files.writeString(sedmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version3" level="1" version="3">
                  <listOfModels>
                    <model id="model1" source="model.xml"/>
                  </listOfModels>
                </sedML>
                """, StandardCharsets.UTF_8);
        var repository = new RecordingGraphRepository();
        var service = new SedmlModelIndexingService(
                new ModelSourceDiscovery(),
                new UriModelSourceContentLoader(),
                new SedmlModelParser(),
                new ParsedModelGraphMapper(),
                repository
        );

        var indexedModels = service.indexModelFiles(tempDirectory);

        assertEquals(1, indexedModels.size());
        assertEquals("uid-1", indexedModels.getFirst().identifier().uid());
        assertEquals("simulation.sedml", indexedModels.getFirst().identifier().fileId());
        assertEquals(1, repository.graphs.size());
        assertEquals("simulation.sedml", repository.graphs.getFirst().reference().fileId());
        assertEquals("simulation.sedml", repository.graphs.getFirst().nodes().get(1).properties().get("modelId"));

        var document = repository.graphs.getFirst().sourceDocument();
        assertNotNull(document);
        assertEquals(Files.readString(sedmlFile, StandardCharsets.UTF_8), document.content());
        assertArrayEquals(Files.readAllBytes(sedmlFile), document.contentBytes());
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

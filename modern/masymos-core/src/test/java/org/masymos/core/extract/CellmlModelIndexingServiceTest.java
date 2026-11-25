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

class CellmlModelIndexingServiceTest {
    Path tempDirectory;

    @BeforeEach
    void createTestDirectory() throws IOException {
        tempDirectory = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
    }

    @Test
    void discoversParsesMapsAndStoresCellmlModelFiles() throws IOException {
        var cellmlFile = tempDirectory.resolve("demo.cellml");
        Files.writeString(cellmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.1#"
                    xmlns:cmeta="http://www.cellml.org/metadata/1.0#"
                    name="demo_cellml"
                    cmeta:id="model_meta">
                  <component name="environment">
                    <variable name="time" units="second"/>
                  </component>
                </model>
                """, StandardCharsets.UTF_8);
        var repository = new RecordingGraphRepository();
        var service = new CellmlModelIndexingService(
                new ModelSourceDiscovery(),
                new UriModelSourceContentLoader(),
                new CellmlModelParser(),
                new ParsedModelGraphMapper(),
                repository
        );

        var indexedModels = service.indexModelFiles(tempDirectory);

        assertEquals(1, indexedModels.size());
        assertEquals("uid-1", indexedModels.getFirst().identifier().uid());
        assertEquals("demo.cellml", indexedModels.getFirst().identifier().fileId());
        assertEquals(1, repository.graphs.size());

        var graph = repository.graphs.getFirst();
        assertEquals("demo.cellml", graph.reference().fileId());
        assertEquals("model_meta", graph.nodes().get(1).properties().get("modelId"));
        assertEquals("demo_cellml", graph.nodes().get(1).properties().get("name"));
        assertEquals("environment", graph.nodes().get(1).properties().get("component"));
        assertEquals("environment/time time environment", graph.nodes().get(1).properties().get("variable"));

        var document = graph.sourceDocument();
        assertNotNull(document);
        assertEquals(Files.readString(cellmlFile, StandardCharsets.UTF_8), document.content());
        assertArrayEquals(Files.readAllBytes(cellmlFile), document.contentBytes());
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

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

class OwlOntologyIndexingServiceTest {
    Path tempDirectory;

    @BeforeEach
    void createTestDirectory() throws IOException {
        tempDirectory = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
    }

    @Test
    void discoversParsesMapsAndStoresOwlFiles() throws IOException {
        var owlFile = tempDirectory.resolve("kisao.owl");
        Files.writeString(owlFile, """
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://www.biomodels.net/kisao/KISAO_FULL#"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:Class rdf:about="http://www.biomodels.net/kisao/KISAO#KISAO_0000000">
                    <rdfs:label>algorithm</rdfs:label>
                  </owl:Class>
                </rdf:RDF>
                """, StandardCharsets.UTF_8);
        var repository = new RecordingGraphRepository();
        var service = new OwlOntologyIndexingService(
                new ModelSourceDiscovery(),
                new UriModelSourceContentLoader(),
                new OwlOntologyParser(),
                new ParsedOntologyGraphMapper(),
                repository
        );

        var indexedModels = service.indexModelFiles(tempDirectory);

        assertEquals(1, indexedModels.size());
        assertEquals("uid-1", indexedModels.getFirst().identifier().uid());
        assertEquals("kisao.owl", indexedModels.getFirst().identifier().fileId());
        assertEquals(1, repository.graphs.size());
        assertEquals("OWL", repository.graphs.getFirst().reference().modelType().name());
        assertEquals("KISAOOntology", repository.graphs.getFirst().nodes().get(1).properties().get("modelId"));

        var document = repository.graphs.getFirst().sourceDocument();
        assertNotNull(document);
        assertEquals(Files.readString(owlFile, StandardCharsets.UTF_8), document.content());
        assertArrayEquals(Files.readAllBytes(owlFile), document.contentBytes());
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

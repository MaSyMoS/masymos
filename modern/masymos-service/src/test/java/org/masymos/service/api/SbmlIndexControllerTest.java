package org.masymos.service.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

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
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.extract.UriModelSourceContentLoader;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class SbmlIndexControllerTest {
    private Path testDirectory;

    @BeforeEach
    void createTestDirectory() throws IOException {
        testDirectory = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
    }

    @Test
    void indexesSbmlSources() throws Exception {
        var sbmlFile = testDirectory.resolve("api-demo.sbml");
        Files.writeString(sbmlFile, """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version2/core" level="3" version="2">
                  <model id="api_demo" name="API demo"/>
                </sbml>
                """, StandardCharsets.UTF_8);
        var repository = new RecordingGraphRepository();
        var mockMvc = mockMvc(repository);

        mockMvc.perform(post("/api/v1/indexes/sbml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sources": ["%s"]
                                }
                                """.formatted(sbmlFile.toUri())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].uid").value("uid-1"))
                .andExpect(jsonPath("$[0].fileId").value("api-demo.sbml"));
    }

    @Test
    void returnsValidationProblemForMissingSources() throws Exception {
        var mockMvc = mockMvc(new RecordingGraphRepository());

        mockMvc.perform(post("/api/v1/indexes/sbml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sources": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    private MockMvc mockMvc(ExtractedModelGraphRepository repository) {
        var indexingService = new SbmlModelIndexingService(
                new ModelSourceDiscovery(),
                new UriModelSourceContentLoader(),
                new SbmlModelParser(),
                new ParsedModelGraphMapper(),
                repository
        );
        return standaloneSetup(new SbmlIndexController(indexingService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
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

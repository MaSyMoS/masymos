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
import org.masymos.service.config.IndexBoundsProperties;
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

    /**
     * Task {@code C04-1} / finding {@code GAP02-002b}, decision 49: a run that stops on one document
     * still says what it wrote. The second source is not SBML, so the run writes the first, refuses the
     * second, and the answer names both - before this, the caller got an unmapped {@code 500} and no word
     * about the document already in the graph.
     */
    @Test
    void aRunThatFailsOnOneDocumentNamesWhatItAlreadyWrote() throws Exception {
        var good = testDirectory.resolve("first.sbml");
        Files.writeString(good, """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version2/core" level="3" version="2">
                  <model id="first" name="First"/>
                </sbml>
                """, StandardCharsets.UTF_8);
        var bad = testDirectory.resolve("second.sbml");
        Files.writeString(bad, "<notSbml/>", StandardCharsets.UTF_8);

        mockMvc(new RecordingGraphRepository())
                .perform(post("/api/v1/indexes/sbml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sources": ["%s", "%s"]
                                }
                                """.formatted(good.toUri(), bad.toUri())))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("https://masymos.org/problems/partial-index"))
                .andExpect(jsonPath("$.title").value("Index run incomplete"))
                .andExpect(jsonPath("$.outcomes.length()").value(2))
                .andExpect(jsonPath("$.outcomes[0].status").value("INDEXED"))
                .andExpect(jsonPath("$.outcomes[0].documents[0].uid").value("uid-1"))
                .andExpect(jsonPath("$.outcomes[1].status").value("FAILED"))
                .andExpect(jsonPath("$.outcomes[1].sourceUri").value(bad.toUri().toString()))
                .andExpect(jsonPath("$.outcomes[1].detail").isString());
    }

    @Test
    void returnsValidationProblemForMissingSources() throws Exception {        var mockMvc = mockMvc(new RecordingGraphRepository());

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
        return standaloneSetup(new SbmlIndexController(indexingService, IndexBoundsProperties.defaults()))
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

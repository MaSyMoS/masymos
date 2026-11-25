package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.masymos.core.extract.CellmlExternalCmetaResolver;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.OwlOntologyIndexingService;
import org.masymos.core.extract.OwlOntologyParser;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.ParsedOntologyGraphMapper;
import org.masymos.core.extract.SedmlModelIndexingService;
import org.masymos.core.extract.SedmlModelParser;
import org.masymos.core.extract.UriModelSourceContentLoader;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.SedmlLinkingResult;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.masymos.core.spi.SedmlLinkingRepository;
import org.masymos.service.config.IndexBoundsProperties;
import org.masymos.service.config.SedmlLinkProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class AdditionalIndexControllerTest {
    private Path testDirectory;

    @BeforeEach
    void createTestDirectory() throws IOException {
        testDirectory = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
    }

    @Test
    void indexesCellmlSources() throws Exception {
        var file = write("api-demo.cellml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.1#" name="api_demo">
                  <component name="environment">
                    <variable name="time" units="second"/>
                  </component>
                </model>
                """);

        mockMvc(new RecordingGraphRepository()).perform(indexRequest("/api/v1/indexes/cellml", file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].fileId").value("api-demo.cellml"));
    }

    @Test
    void indexesCellmlSourcesWithPmrExternalCmetaOption() throws Exception {
        var file = write("api-demo-pmr.cellml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.1#" name="api_demo_pmr"/>
                """);

        mockMvc(new RecordingGraphRepository()).perform(indexRequest(
                        "/api/v1/indexes/cellml",
                        file,
                        """
                          "externalCmeta": "pmr"
                        """
                ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].fileId").value("api-demo-pmr.cellml"));
    }

    @Test
    void rejectsUnsupportedCellmlExternalCmetaOption() throws Exception {
        var file = write("api-demo-invalid.cellml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.1#" name="api_demo_invalid"/>
                """);

        mockMvc(new RecordingGraphRepository()).perform(indexRequest(
                        "/api/v1/indexes/cellml",
                        file,
                        """
                          "externalCmeta": "other"
                        """
                ))
                .andExpect(status().isBadRequest());
    }

    @Test
    void indexesSedmlSources() throws Exception {
        var file = write("api-demo.sedml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version3" level="1" version="3">
                  <listOfModels>
                    <model id="model1" source="model.xml"/>
                  </listOfModels>
                </sedML>
                """);
        var linker = new RecordingSedmlLinkingRepository();

        mockMvc(new RecordingGraphRepository(), linker, true)
                .perform(indexRequest("/api/v1/indexes/sedml", file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].fileId").value("api-demo.sedml"))
                .andExpect(jsonPath("$[0].linkStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[0].links.modelReferenceLinks").value(2))
                .andExpect(jsonPath("$[0].links.sbmlObservationLinks").value(3))
                .andExpect(jsonPath("$[0].links.cellmlObservationLinks").value(5));

        assertEquals(1, linker.invocations);
    }

    @Test
    void skipsSedmlLinkingWhenAutoLinkIsDisabled() throws Exception {
        var file = write("api-demo-no-link.sedml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version3" level="1" version="3">
                  <listOfModels>
                    <model id="model1" source="model.xml"/>
                  </listOfModels>
                </sedML>
                """);
        var linker = new RecordingSedmlLinkingRepository();

        mockMvc(new RecordingGraphRepository(), linker, false)
                .perform(indexRequest("/api/v1/indexes/sedml", file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].fileId").value("api-demo-no-link.sedml"))
                .andExpect(jsonPath("$[0].linkStatus").value("SKIPPED"))
                .andExpect(jsonPath("$[0].links").doesNotExist());

        assertEquals(0, linker.invocations);
    }

    @Test
    void indexesOwlSources() throws Exception {
        var file = write("api-demo.owl", """
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://example.org/api#"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:Class rdf:about="TERM_1">
                    <rdfs:label>term</rdfs:label>
                  </owl:Class>
                </rdf:RDF>
                """);

        mockMvc(new RecordingGraphRepository()).perform(indexRequest("/api/v1/indexes/owl", file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].fileId").value("api-demo.owl"));
    }

    private Path write(String filename, String content) throws IOException {
        var path = testDirectory.resolve(filename);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private org.springframework.test.web.servlet.RequestBuilder indexRequest(String endpoint, Path file) {
        return indexRequest(endpoint, file, "");
    }

    private org.springframework.test.web.servlet.RequestBuilder indexRequest(String endpoint, Path file, String extraJson) {
        var optionalComma = extraJson.isBlank() ? "" : ",";
        return post(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "sources": ["%s"]%s
                        %s
                        }
                        """.formatted(file.toUri(), optionalComma, extraJson));
    }

    private MockMvc mockMvc(ExtractedModelGraphRepository repository) {
        return mockMvc(repository, () -> new SedmlLinkingResult(0, 0, 0), true);
    }

    private MockMvc mockMvc(
            ExtractedModelGraphRepository repository,
            SedmlLinkingRepository linkingRepository,
            boolean autoLink
    ) {
        var discovery = new ModelSourceDiscovery();
        var contentLoader = new UriModelSourceContentLoader();
        var modelGraphMapper = new ParsedModelGraphMapper();
        return standaloneSetup(
                new CellmlIndexController(new CellmlModelIndexingService(
                        discovery, contentLoader, new CellmlModelParser(), modelGraphMapper, repository
                ), CellmlExternalCmetaResolver.NONE, IndexBoundsProperties.defaults()),
                new SedmlIndexController(new SedmlModelIndexingService(
                        discovery, contentLoader, new SedmlModelParser(), modelGraphMapper, repository
                ), linkingRepository, new SedmlLinkProperties(autoLink), IndexBoundsProperties.defaults()),
                new OwlIndexController(new OwlOntologyIndexingService(
                        discovery, contentLoader, new OwlOntologyParser(), new ParsedOntologyGraphMapper(), repository
                ), IndexBoundsProperties.defaults())
        ).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static final class RecordingSedmlLinkingRepository implements SedmlLinkingRepository {
        private int invocations;

        @Override
        public SedmlLinkingResult linkSedmlReferences() {
            invocations++;
            return new SedmlLinkingResult(2, 3, 5);
        }
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

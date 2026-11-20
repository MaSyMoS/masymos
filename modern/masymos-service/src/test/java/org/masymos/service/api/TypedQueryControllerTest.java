package org.masymos.service.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.ArchiveSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.SedmlSearchResult;
import org.masymos.core.spi.ArchiveQueryRepository;
import org.masymos.core.spi.SedmlQueryRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class TypedQueryControllerTest {
    @Test
    void mapsSedmlHitsIncludingReferencedModelSources() throws Exception {
        mockMvc().perform(query("/api/v1/queries/sedml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uid").value("sedml-1"))
                .andExpect(jsonPath("$[0].fileId").value("experiment.sedml"))
                .andExpect(jsonPath("$[0].modelName").value("Glycolysis experiment"))
                .andExpect(jsonPath("$[0].documentUri").value("https://example.org/experiment.sedml"))
                .andExpect(jsonPath("$[0].versionId").value("v2"))
                .andExpect(jsonPath("$[0].score").value(0.75))
                .andExpect(jsonPath("$[0].referencedModelUris[0]").value("urn:miriam:biomodels.db:BIOMD0000000001"));
    }

    @Test
    void mapsArchiveHitsIncludingDocumentCount() throws Exception {
        mockMvc().perform(query("/api/v1/queries/archives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uid").value("https://example.org/archive.omex"))
                .andExpect(jsonPath("$[0].fileId").value("archive.omex"))
                .andExpect(jsonPath("$[0].sourceUri").value("https://example.org/archive.omex"))
                .andExpect(jsonPath("$[0].score").value(1.0))
                .andExpect(jsonPath("$[0].documentCount").value(2));
    }

    @Test
    void rejectsARequestWithoutKeywordOrClause() throws Exception {
        mockMvc().perform(post("/api/v1/queries/sedml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void rejectsAnUnknownModelType() throws Exception {
        mockMvc().perform(post("/api/v1/queries/sedml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "x",
                                  "modelTypes": ["OWL"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("SBML, CELLML")));
    }

    private static org.springframework.test.web.servlet.RequestBuilder query(String path) {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "keyword": "glycolysis"
                        }
                        """);
    }

    private MockMvc mockMvc() {
        return standaloneSetup(new TypedQueryController(new FakeSedmlQueryRepository(), new FakeArchiveQueryRepository()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static final class FakeSedmlQueryRepository implements SedmlQueryRepository {
        @Override
        public List<SedmlSearchResult> querySedml(ModelQueryRequest request) {
            return List.of(new SedmlSearchResult(
                    "sedml-1",
                    "experiment.sedml",
                    "Glycolysis experiment",
                    URI.create("https://example.org/experiment.sedml"),
                    "v2",
                    0.75,
                    List.of(URI.create("urn:miriam:biomodels.db:BIOMD0000000001"))));
        }
    }

    private static final class FakeArchiveQueryRepository implements ArchiveQueryRepository {
        @Override
        public List<ArchiveSearchResult> queryArchives(ModelQueryRequest request) {
            return List.of(new ArchiveSearchResult(
                    "https://example.org/archive.omex",
                    "archive.omex",
                    URI.create("https://example.org/archive.omex"),
                    1.0,
                    2L));
        }
    }
}

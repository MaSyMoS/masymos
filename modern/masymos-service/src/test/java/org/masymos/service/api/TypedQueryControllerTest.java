package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.ArchiveSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.Page;
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
                .andExpect(jsonPath("$.hits[0].uid").value("sedml-1"))
                .andExpect(jsonPath("$.hits[0].fileId").value("experiment.sedml"))
                .andExpect(jsonPath("$.hits[0].modelName").value("Glycolysis experiment"))
                .andExpect(jsonPath("$.hits[0].documentUri").value("https://example.org/experiment.sedml"))
                .andExpect(jsonPath("$.hits[0].versionId").value("v2"))
                .andExpect(jsonPath("$.hits[0].score").value(0.75))
                .andExpect(jsonPath("$.hits[0].referencedModelUris[0]").value("urn:miriam:biomodels.db:BIOMD0000000001"));
    }

    @Test
    void mapsArchiveHitsIncludingDocumentCount() throws Exception {
        mockMvc().perform(query("/api/v1/queries/archives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].uid").value("https://example.org/archive.omex"))
                .andExpect(jsonPath("$.hits[0].fileId").value("archive.omex"))
                .andExpect(jsonPath("$.hits[0].sourceUri").value("https://example.org/archive.omex"))
                .andExpect(jsonPath("$.hits[0].score").value(1.0))
                .andExpect(jsonPath("$.hits[0].documentCount").value(2));
    }

    /**
     * {@code OPN-6}: the typed endpoints answer the same page envelope as the model endpoint, and the
     * cursor travels as a request parameter rather than in the body.
     */
    @Test
    void answersTheTypedEndpointsAsPagesWithTheirNextCursor() throws Exception {
        var sedml = new FakeSedmlQueryRepository();
        sedml.nextCursor = "cursor-sedml";
        var archives = new FakeArchiveQueryRepository();
        archives.nextCursor = "cursor-archives";
        var mockMvc = mockMvc(sedml, archives);

        mockMvc.perform(query("/api/v1/queries/sedml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].uid").value("sedml-1"))
                .andExpect(jsonPath("$.nextCursor").value("cursor-sedml"));
        mockMvc.perform(query("/api/v1/queries/archives"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].documentCount").value(2))
                .andExpect(jsonPath("$.nextCursor").value("cursor-archives"));
    }

    @Test
    void passesTheTypedCursorRequestParameterAsTheRequestCursor() throws Exception {
        var sedml = new FakeSedmlQueryRepository();
        var archives = new FakeArchiveQueryRepository();
        var mockMvc = mockMvc(sedml, archives);

        mockMvc.perform(post("/api/v1/queries/sedml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("cursor", "cursor-1")
                        .content("""
                                {
                                  "keyword": "glycolysis"
                                }
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/queries/archives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("cursor", "cursor-2")
                        .content("""
                                {
                                  "keyword": "glycolysis"
                                }
                                """))
                .andExpect(status().isOk());

        assertEquals("cursor-1", sedml.lastRequest.cursor(), "the sedml endpoint forwards the cursor");
        assertEquals("cursor-2", archives.lastRequest.cursor(), "the archive endpoint forwards the cursor");
    }

    @Test
    void answersBadRequestWhenTheTypedRepositoryRefusesTheCursor() throws Exception {
        var sedml = new FakeSedmlQueryRepository();
        sedml.refuseCursor("the cursor does not belong to this request");
        var archives = new FakeArchiveQueryRepository();
        archives.refuseCursor("the cursor does not belong to this request");
        var mockMvc = mockMvc(sedml, archives);

        for (var endpoint : List.of("/api/v1/queries/sedml", "/api/v1/queries/archives")) {
            mockMvc.perform(post(endpoint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .param("cursor", "foreign-cursor")
                            .content("""
                                    {
                                      "keyword": "glycolysis"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("the cursor does not belong to this request"));
        }
    }

    @Test
    void rejectsARequestWithoutKeywordOrClause() throws Exception {
        mockMvc().perform(post("/api/v1/queries/sedml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    /**
     * {@code GAP02-006}: the two typed endpoints state that {@code modelTypes} is ignored, so a value
     * only the model query can filter on must be ignored rather than rejected - and the domain request
     * must arrive with no kind filter and with the grouping flag off, whatever the payload said.
     */
    @Test
    void answersTheUnfilteredSedmlEndpointForANonApplicableModelType() throws Exception {
        var sedml = new FakeSedmlQueryRepository();
        var archives = new FakeArchiveQueryRepository();
        var mockMvc = mockMvc(sedml, archives);

        mockMvc.perform(post("/api/v1/queries/sedml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "x",
                                  "modelTypes": ["SEDML"],
                                  "groupByVersion": true
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/queries/archives")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "x",
                                  "modelTypes": ["OWL"]
                                }
                                """))
                .andExpect(status().isOk());

        assertEquals(Set.of(), sedml.lastRequest.modelTypes(), "the kind filter is not applied to a description");
        assertFalse(sedml.lastRequest.groupByVersion(), "a description has no versions to group");
        assertEquals(Set.of(), archives.lastRequest.modelTypes(), "and an archive has no model kind");
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
        return mockMvc(new FakeSedmlQueryRepository(), new FakeArchiveQueryRepository());
    }

    private MockMvc mockMvc(SedmlQueryRepository sedml, ArchiveQueryRepository archives) {
        return standaloneSetup(new TypedQueryController(sedml, archives))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static final class FakeSedmlQueryRepository implements SedmlQueryRepository {
        private ModelQueryRequest lastRequest;
        private String nextCursor;
        private String refusedCursorReason;

        private void refuseCursor(String reason) {
            refusedCursorReason = reason;
        }

        private void refuseIfAsked(ModelQueryRequest request) {
            if (refusedCursorReason != null && request.cursor() != null) {
                throw new IllegalArgumentException(refusedCursorReason);
            }
        }

        @Override
        public List<SedmlSearchResult> querySedml(ModelQueryRequest request) {
            return querySedmlPage(request).hits();
        }

        @Override
        public Page<SedmlSearchResult> querySedmlPage(ModelQueryRequest request) {
            lastRequest = request;
            refuseIfAsked(request);
            return new Page<>(
                    List.of(new SedmlSearchResult(
                            "sedml-1",
                            "experiment.sedml",
                            "Glycolysis experiment",
                            URI.create("https://example.org/experiment.sedml"),
                            "v2",
                            0.75,
                            List.of(URI.create("urn:miriam:biomodels.db:BIOMD0000000001")))),
                    nextCursor);
        }
    }

    private static final class FakeArchiveQueryRepository implements ArchiveQueryRepository {
        private ModelQueryRequest lastRequest;
        private String nextCursor;
        private String refusedCursorReason;

        private void refuseCursor(String reason) {
            refusedCursorReason = reason;
        }

        private void refuseIfAsked(ModelQueryRequest request) {
            if (refusedCursorReason != null && request.cursor() != null) {
                throw new IllegalArgumentException(refusedCursorReason);
            }
        }

        @Override
        public List<ArchiveSearchResult> queryArchives(ModelQueryRequest request) {
            return queryArchivesPage(request).hits();
        }

        @Override
        public Page<ArchiveSearchResult> queryArchivesPage(ModelQueryRequest request) {
            lastRequest = request;
            refuseIfAsked(request);
            return new Page<>(
                    List.of(new ArchiveSearchResult(
                            "https://example.org/archive.omex",
                            "archive.omex",
                            URI.create("https://example.org/archive.omex"),
                            1.0,
                            2L)),
                    nextCursor);
        }
    }
}

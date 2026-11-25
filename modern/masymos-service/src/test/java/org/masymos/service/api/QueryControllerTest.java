package org.masymos.service.api;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelDeletionResult;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.model.ModelVersion;
import org.masymos.core.model.StoredDocument;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.ModelVersionHit;
import org.masymos.core.query.PersonSearchResult;
import org.masymos.core.query.PersonSummary;
import org.masymos.core.query.PublicationSearchResult;
import org.masymos.core.query.QueryField;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ResourceQueryRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class QueryControllerTest {
    @Test
    void rejectsEmptyQueryRequest() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository());

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void mapsDomainResultsToApiResponse() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository());

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak",
                                  "top": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uid").value("uid-1"))
                .andExpect(jsonPath("$[0].modelName").value("Novak model"));
    }

    @Test
    void mapsResourceQueryResultsToApiResponses() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository());

        mockMvc.perform(query("/api/v1/queries/annotations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uri").value("https://identifiers.org/go/GO:0008150"))
                .andExpect(jsonPath("$[0].relatedModelUris[0]").value("https://example.org/model.xml"));
        mockMvc.perform(query("/api/v1/queries/persons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].person.familyName").value("Doe"));
        mockMvc.perform(query("/api/v1/queries/publications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Publication title"))
                .andExpect(jsonPath("$[0].authors[0].familyName").value("Doe"));
    }

    @Test
    void rejectsNegativeScoreFloor() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository());

        mockMvc.perform(post("/api/v1/queries/annotations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak",
                                  "minScore": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void passesScoreFloorToResourceQuery() throws Exception {
        var resourceRepository = new FakeResourceQueryRepository();
        var mockMvc = mockMvc(new FakeModelRepository(), resourceRepository);

        mockMvc.perform(post("/api/v1/queries/annotations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak",
                                  "minScore": 0.5
                                }
                                """))
                .andExpect(status().isOk());

        assertEquals(0.5, resourceRepository.lastAnnotationRequest.minScore());
    }

    @Test
    void passesGroupByVersionFlagToModelRepository() throws Exception {
        var repository = new FakeModelRepository();
        var mockMvc = mockMvc(repository);

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak",
                                  "groupByVersion": true
                                }
                                """))
                .andExpect(status().isOk());

        assertTrue(repository.lastModelRequest.groupByVersion());
    }

    @Test
    void ungroupedModelResponseCarriesEmptyVersions() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository());

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versions").isArray())
                .andExpect(jsonPath("$[0].versions").isEmpty());
    }

    @Test
    void ungroupedModelResponseCarriesReachabilityFields() throws Exception {
        var repository = new FakeModelRepository();
        repository.modelResults = List.of(new ModelSearchResult(
                "uid-1",
                "model-1",
                "Novak model",
                URI.create("https://example.org/model.xml"),
                "model.xml",
                0.9,
                List.of(),
                "v2",
                List.of(QueryField.NAME, QueryField.KEYWORD),
                "modelFulltextV6",
                true,
                20480L,
                false));
        var mockMvc = mockMvc(repository);

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].versionId").value("v2"))
                .andExpect(jsonPath("$[0].matchedFields.length()").value(2))
                .andExpect(jsonPath("$[0].matchedFields[0]").value("NAME"))
                .andExpect(jsonPath("$[0].matchedFields[1]").value("KEYWORD"))
                .andExpect(jsonPath("$[0].indexSource").value("modelFulltextV6"))
                .andExpect(jsonPath("$[0].documentStored").value(true))
                .andExpect(jsonPath("$[0].documentBytes").value(20480))
                .andExpect(jsonPath("$[0].documentTruncated").value(false));
    }

    @Test
    void hitWithoutStoredDocumentReportsAbsenceNotZero() throws Exception {
        var repository = new FakeModelRepository();
        repository.modelResults = List.of(new ModelSearchResult(
                "uid-2",
                "model-2",
                "Unstored model",
                URI.create("https://example.org/unstored.xml"),
                "unstored.xml",
                0.5,
                List.of(),
                "v1",
                List.of(QueryField.URI),
                "exact",
                false,
                null,
                false));
        var mockMvc = mockMvc(repository);

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentStored").value(false))
                .andExpect(jsonPath("$[0].documentBytes").value(nullValue()))
                .andExpect(jsonPath("$[0].documentTruncated").value(false))
                .andExpect(jsonPath("$[0].indexSource").value("exact"));
    }

    @Test
    void serialisesGroupedModelVersions() throws Exception {
        var repository = new FakeModelRepository();
        repository.modelResults = List.of(new ModelSearchResult(
                "uid-1",
                "model-1",
                "Novak model",
                URI.create("https://example.org/model.xml"),
                "model.xml",
                0.9,
                List.of(
                        new ModelVersionHit("uid-1-v2", "v2", 0.9),
                        new ModelVersionHit("uid-1-v1", "v1", 0.4))));
        var mockMvc = mockMvc(repository);

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak",
                                  "groupByVersion": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].score").value(0.9))
                .andExpect(jsonPath("$[0].versions.length()").value(2))
                .andExpect(jsonPath("$[0].versions[0].uid").value("uid-1-v2"))
                .andExpect(jsonPath("$[0].versions[0].versionId").value("v2"))
                .andExpect(jsonPath("$[0].versions[0].score").value(0.9))
                .andExpect(jsonPath("$[0].versions[1].uid").value("uid-1-v1"))
                .andExpect(jsonPath("$[0].versions[1].versionId").value("v1"))
                .andExpect(jsonPath("$[0].versions[1].score").value(0.4));
    }

    @Test
    void ignoresGroupByVersionFlagForAnnotations() throws Exception {
        var resourceRepository = new FakeResourceQueryRepository();
        var mockMvc = mockMvc(new FakeModelRepository(), resourceRepository);

        mockMvc.perform(post("/api/v1/queries/annotations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "novak",
                                  "groupByVersion": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uri").value("https://identifiers.org/go/GO:0008150"));
    }

    @Test
    void passesModelTypeFilterToModelRepository() throws Exception {
        var repository = new FakeModelRepository();
        var mockMvc = mockMvc(repository);

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "x",
                                  "modelTypes": ["SBML"]
                                }
                                """))
                .andExpect(status().isOk());

        assertEquals(Set.of(ModelType.SBML), repository.lastModelRequest.modelTypes());
    }

    @Test
    void rejectsAKindWithoutAQueryEndpoint() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository());

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "x",
                                  "modelTypes": ["OWL"]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnUnknownModelTypeValue() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository());

        mockMvc.perform(post("/api/v1/queries/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "x",
                                  "modelTypes": ["nope"]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.RequestBuilder query(String path) {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "keyword": "novak"
                        }
                        """);
    }

    private MockMvc mockMvc(ModelRepository repository) {
        return mockMvc(repository, new FakeResourceQueryRepository());
    }

    private MockMvc mockMvc(ModelRepository repository, FakeResourceQueryRepository resourceRepository) {
        return standaloneSetup(new QueryController(repository, resourceRepository))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static final class FakeModelRepository implements ModelRepository {
        private ModelQueryRequest lastModelRequest;
        private List<ModelSearchResult> modelResults = List.of(new ModelSearchResult(
                "uid-1",
                "model-1",
                "Novak model",
                URI.create("https://example.org/model.xml"),
                "model.xml",
                1.0
        ));

        @Override
        public ModelIdentifier insert(ModelReference modelReference) {
            return new ModelIdentifier("uid-1", modelReference.fileId());
        }

        @Override
        public Optional<ModelIdentifier> findByUid(String uid) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelVersion> findLatestVersion(String fileId) {
            return Optional.empty();
        }

        @Override
        public List<ModelVersion> findVersions(String fileId) {
            return List.of();
        }

        @Override
        public Optional<ModelVersion> findVersion(String fileId, String versionId) {
            return Optional.empty();
        }

        @Override
        public Optional<StoredDocument> findDocument(String uid) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelDeletionResult> deleteByUid(String uid, String expectedFileId, String expectedVersionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelDeletionResult> deleteByFileId(String fileId) {
            return Optional.empty();
        }

        @Override
        public List<ModelSearchResult> queryModels(ModelQueryRequest request) {
            lastModelRequest = request;
            return modelResults;
        }
    }

    private static final class FakeResourceQueryRepository implements ResourceQueryRepository {
        private ModelQueryRequest lastAnnotationRequest;

        @Override
        public List<AnnotationSearchResult> queryAnnotations(ModelQueryRequest request) {
            lastAnnotationRequest = request;
            return List.of(new AnnotationSearchResult(
                    "https://identifiers.org/go/GO:0008150",
                    List.of(URI.create("https://example.org/model.xml")),
                    1.0
            ));
        }

        @Override
        public List<PersonSearchResult> queryPersons(ModelQueryRequest request) {
            return List.of(new PersonSearchResult(
                    new PersonSummary("Jane", "Doe", "jane@example.org", "Example Institute"),
                    List.of(URI.create("https://example.org/model.xml")),
                    1.0
            ));
        }

        @Override
        public List<PublicationSearchResult> queryPublications(ModelQueryRequest request) {
            return List.of(new PublicationSearchResult(
                    "Publication title",
                    "Journal",
                    "Institute",
                    "2026",
                    List.of(new PersonSummary("Jane", "Doe", "", "")),
                    List.of(URI.create("https://example.org/model.xml")),
                    1.0
            ));
        }
    }
}

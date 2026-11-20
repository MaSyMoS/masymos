package org.masymos.service.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelDeletionResult;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelVersion;
import org.masymos.core.model.StoredDocument;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.PersonSearchResult;
import org.masymos.core.query.PublicationSearchResult;
import org.masymos.core.query.QueryField;
import org.masymos.core.query.fusion.BestRankStrategy;
import org.masymos.core.query.fusion.BordaCombMnzStrategy;
import org.masymos.core.query.fusion.ModelRow;
import org.masymos.core.query.fusion.RankFusionQueryService;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ModelRowLookup;
import org.masymos.core.spi.ResourceQueryRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class AggregatedQueryControllerTest {
    private static final String MODEL_URI = "https://example.org/model.xml";
    private static final String AGGREGATED_PATH = "/api/v1/queries/aggregated";

    @Test
    void rejectsARequestWithoutKeywordOrClause() throws Exception {
        mockMvc().perform(aggregated("""
                        {"strategy": "bordaCombMnz"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void rejectsAMissingStrategy() throws Exception {
        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "  "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void rejectsAnUnknownStrategy() throws Exception {
        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "doesNotExist"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("bordaCombMnz")))
                .andExpect(jsonPath("$.detail").value(containsString("bestRank")));
    }

    @Test
    void rejectsAnUnknownWeightKey() throws Exception {
        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz", "weights": {"model": 4, "nonsense": 1}}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsANonPositiveWeight() throws Exception {
        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz", "weights": {"model": 0}}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsAFusedHitIncludingProvenance() throws Exception {
        // one row, found at rank 1 by the model and by the annotation ranker:
        // (1*1 + 1*1) * 2 / (2 * 2) = 1.0
        double expected = (1.0 + 1.0) * 2 / (2.0 * 2);

        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].uid").value("uid-1"))
                .andExpect(jsonPath("$[0].fileId").value("model.xml"))
                .andExpect(jsonPath("$[0].filename").value("model.xml"))
                .andExpect(jsonPath("$[0].fusedScore").value(expected))
                .andExpect(jsonPath("$[0].sources[0].type").value("model"))
                .andExpect(jsonPath("$[0].sources[0].rank").value(1))
                .andExpect(jsonPath("$[0].sources[1].type").value("annotation"))
                .andExpect(jsonPath("$[0].sources[1].rank").value(1));
    }

    @Test
    void returnsNullFusedScoreForTheOrderOnlyStrategy() throws Exception {
        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "bestRank"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].uid").value("uid-1"))
                .andExpect(jsonPath("$[0].fusedScore").value(nullValue()))
                .andExpect(jsonPath("$[0].sources[0].type").value("model"))
                .andExpect(jsonPath("$[0].sources[0].rank").value(1));
    }

    @Test
    void listsTheStrategiesWithTheirScoreSemantics() throws Exception {
        mockMvc().perform(get("/api/v1/queries/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("bestRank"))
                .andExpect(jsonPath("$[0].producesScore").value(false))
                .andExpect(jsonPath("$[0].description").exists())
                .andExpect(jsonPath("$[1].id").value("bordaCombMnz"))
                .andExpect(jsonPath("$[1].producesScore").value(true))
                .andExpect(jsonPath("$[1].description").exists());
    }

    @Test
    void narrowsTheAggregationToTheSelectedEntity() throws Exception {
        // one model hit at rank 1 with only the model ranker selected: (1*1) * 2 / (2*1) = 1.0
        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz", "types": ["model"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].uid").value("uid-1"))
                .andExpect(jsonPath("$[0].fusedScore").value(1.0))
                .andExpect(jsonPath("$[0].sources.length()").value(1))
                .andExpect(jsonPath("$[0].sources[0].type").value("model"));
    }

    @Test
    void rejectsAnEntityWithItsOwnEndpoint() throws Exception {
        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz", "types": ["sedml"]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("model")))
                .andExpect(jsonPath("$.detail").value(containsString("publication")));
    }

    @Test
    void rejectsAnEmptyEntitySelection() throws Exception {
        mockMvc().perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz", "types": []}
                        """))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.RequestBuilder aggregated(String body) {
        return post(AGGREGATED_PATH).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockMvc mockMvc() {
        var service = new RankFusionQueryService(
                new FakeModelRepository(),
                new FakeResourceQueryRepository(),
                new FakeModelRowLookup(),
                List.of(new BordaCombMnzStrategy(), new BestRankStrategy()));
        return standaloneSetup(new AggregatedQueryController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static ModelRow modelRow() {
        return new ModelRow(
                "uid-1", "model.xml", "model-1", "Novak model", URI.create(MODEL_URI), "v2", true, 20480L, false);
    }

    private static final class FakeModelRepository implements ModelRepository {
        @Override
        public List<ModelSearchResult> queryModels(ModelQueryRequest request) {
            return List.of(new ModelSearchResult(
                    "uid-1",
                    "model-1",
                    "Novak model",
                    URI.create(MODEL_URI),
                    "model.xml",
                    1.0,
                    List.of(),
                    "v2",
                    List.of(QueryField.KEYWORD),
                    "modelFulltextV5",
                    true,
                    20480L,
                    false));
        }

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
    }

    private static final class FakeResourceQueryRepository implements ResourceQueryRepository {
        @Override
        public List<AnnotationSearchResult> queryAnnotations(ModelQueryRequest request) {
            return List.of(new AnnotationSearchResult(
                    "https://identifiers.org/go/GO:0008150", List.of(URI.create(MODEL_URI)), 1.0));
        }

        @Override
        public List<PersonSearchResult> queryPersons(ModelQueryRequest request) {
            return List.of();
        }

        @Override
        public List<PublicationSearchResult> queryPublications(ModelQueryRequest request) {
            return List.of();
        }
    }

    private static final class FakeModelRowLookup implements ModelRowLookup {
        @Override
        public List<ModelRow> byDocumentUris(java.util.Collection<URI> documentUris) {
            return List.of(modelRow());
        }
    }
}

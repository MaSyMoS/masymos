package org.masymos.service.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.net.URI;
import java.util.ArrayList;
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
import org.masymos.core.query.fusion.ModelRows;
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

    /**
     * {@code C03-2}: the row-lookup cut reaches the HTTP answer as its own field. It must not be
     * confused with {@code documentTruncated}, which describes the stored copy of the source
     * document - the two answer "the graph's document is cut" and "the search looked at less than
     * the request named", and a caller acts differently on each. Measured red first: before the
     * field existed this path had no way to say anything.
     */
    @Test
    void reportsACutRowLookupSeparatelyFromTheStoredDocumentFlag() throws Exception {
        mockMvc(new FakeModelRowLookup(true))
                .perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rowLookupTruncated").value(true))
                .andExpect(jsonPath("$[0].documentUrisTruncated").value(false))
                .andExpect(jsonPath("$[0].documentTruncated").value(false));
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

    /**
     * {@code C03-5}: the depth an answer ran into reaches the HTTP surface as its own field, and only on
     * a short answer. 51 versions of one file are more rows than the per-ranker depth of 50 for the
     * default {@code top}, they collapse to a single file, and that one row says the depth was the
     * ceiling; 50 versions fill the depth exactly and say nothing, because nothing was left out.
     */
    @Test
    void reportsTheRankerDepthOnAShortAnswer() throws Exception {
        mockMvc(new FakeModelRowLookup(), new FakeModelRepository(51))
                .perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rankerDepthTruncated").value(true))
                .andExpect(jsonPath("$[0].rowLookupTruncated").value(false))
                .andExpect(jsonPath("$[0].documentTruncated").value(false));

        mockMvc(new FakeModelRowLookup(), new FakeModelRepository(50))
                .perform(aggregated("""
                        {"keyword": "novak", "strategy": "bordaCombMnz"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rankerDepthTruncated").value(false));
    }

    private static org.springframework.test.web.servlet.RequestBuilder aggregated(String body) {
        return post(AGGREGATED_PATH).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockMvc mockMvc() {
        return mockMvc(new FakeModelRowLookup());
    }

    private MockMvc mockMvc(ModelRowLookup rows) {
        return mockMvc(rows, new FakeModelRepository());
    }

    private MockMvc mockMvc(ModelRowLookup rows, ModelRepository models) {
        var service = new RankFusionQueryService(
                models,
                new FakeResourceQueryRepository(),
                rows,
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
        private final int versions;

        private FakeModelRepository() {
            this(1);
        }

        /**
         * @param versions how many versions of the one file the model query returns, one {@code uid}
         *     each - enough to fill or overflow the per-ranker depth while collapsing to one file
         */
        private FakeModelRepository(int versions) {
            this.versions = versions;
        }

        @Override
        public List<ModelSearchResult> queryModels(ModelQueryRequest request) {
            var hits = new ArrayList<ModelSearchResult>(versions);
            for (var index = 0; index < versions; index++) {
                hits.add(new ModelSearchResult(
                        "uid-" + (index + 1),
                        "model-1",
                        "Novak model",
                        URI.create(MODEL_URI),
                        "model.xml",
                        1.0 - index * 0.001,
                        List.of(),
                        "v" + index,
                        List.of(QueryField.KEYWORD),
                        "modelFulltextV6",
                        true,
                        20480L,
                        false));
            }
            return List.copyOf(hits);
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
        private final boolean truncated;

        private FakeModelRowLookup() {
            this(false);
        }

        private FakeModelRowLookup(boolean truncated) {
            this.truncated = truncated;
        }

        @Override
        public ModelRows byDocumentUris(java.util.Collection<URI> documentUris) {
            return new ModelRows(List.of(modelRow()), truncated);
        }
    }
}

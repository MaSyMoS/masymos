package org.masymos.service.api;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.masymos.core.spi.OntologySimilarity;
import org.masymos.core.spi.OntologyTerm;
import org.masymos.core.spi.OntologyTraversal;
import org.springframework.test.web.servlet.MockMvc;

class OntologyControllerTest {
    private final FakeOntologyTraversal traversal = new FakeOntologyTraversal();

    private MockMvc mockMvc() {
        return standaloneSetup(new OntologyController(traversal))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void answersATermWhoseIdContainsAColon() throws Exception {
        traversal.terms.put("SBO:0000001", new OntologyTerm("SBO:0000001", Map.of("name", "entity")));

        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/terms/SBO:0000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontology").value("SBOOntology"))
                .andExpect(jsonPath("$.id").value("SBO:0000001"))
                .andExpect(jsonPath("$.properties.name").value("entity"));
    }

    @Test
    void answersTheRootTermWithItsOwlId() throws Exception {
        traversal.root = Optional.of(new OntologyTerm("owl:SBOOntology", Map.of("termType", "root")));

        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/root"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("owl:SBOOntology"))
                .andExpect(jsonPath("$.properties.termType").value("root"));
    }

    @Test
    void answersDepthNullWhenTheTermHasNoPathToTheRoot() throws Exception {
        traversal.terms.put("orphan", new OntologyTerm("orphan", Map.of()));

        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/terms/orphan/depth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("orphan"))
                .andExpect(jsonPath("$.depth").value(nullValue()));
    }

    @Test
    void answersTheDepthWhenThereIsAPath() throws Exception {
        traversal.terms.put("B", new OntologyTerm("B", Map.of()));
        traversal.depth = Optional.of(2);

        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/terms/B/depth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depth").value(2));
    }

    @Test
    void answersNotFoundForAnUnknownTerm() throws Exception {
        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/terms/nowhere/depth"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("not-found")));
    }

    @Test
    void answersNullFieldsWhenNoCommonAncestorIsFoundWithinMaxDepth() throws Exception {
        traversal.terms.put("A", new OntologyTerm("A", Map.of()));
        traversal.terms.put("Z", new OntologyTerm("Z", Map.of()));

        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/similarity?first=A&second=Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.similarity").value(nullValue()))
                .andExpect(jsonPath("$.lowestCommonAncestor").value(nullValue()))
                .andExpect(jsonPath("$.pathLength").value(nullValue()));
    }

    @Test
    void answersTheScoreAndItsAncestor() throws Exception {
        traversal.terms.put("B", new OntologyTerm("B", Map.of()));
        traversal.terms.put("C", new OntologyTerm("C", Map.of()));
        traversal.similarity = Optional.of(new OntologySimilarity(2, "A", 1, 0.36d));

        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/similarity?first=B&second=C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pathLength").value(2))
                .andExpect(jsonPath("$.lowestCommonAncestor").value("A"))
                .andExpect(jsonPath("$.lowestCommonAncestorDepth").value(1))
                .andExpect(jsonPath("$.similarity").value(0.36));
    }

    @Test
    void answersNotFoundWhenTheSecondTermIsNotIndexed() throws Exception {
        traversal.terms.put("B", new OntologyTerm("B", Map.of()));

        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/similarity?first=B&second=missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("not-found")));
    }

    @Test
    void acceptsTheBoundsOfMaxDepth() throws Exception {
        traversal.terms.put("A", new OntologyTerm("A", Map.of()));
        for (var maxDepth : new String[] {"1", "64"}) {
            mockMvc()
                    .perform(get("/api/v1/ontologies/SBOOntology/terms/A/depth?maxDepth=" + maxDepth))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void answersNotFoundWhenTheOntologyIsNotIndexed() throws Exception {
        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/root"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("not-found")));
    }

    @Test
    void answersNotFoundForAnUnknownTermOnTheTermRoute() throws Exception {
        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/terms/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("not-found")));
    }

    @Test
    void rejectsMaxDepthOutsideTheSupportedRange() throws Exception {
        for (var maxDepth : new String[] {"0", "65"}) {
            mockMvc()
                    .perform(get("/api/v1/ontologies/SBOOntology/terms/A/depth?maxDepth=" + maxDepth))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(endsWith("invalid-argument")));
        }
    }

    @Test
    void rejectsASimilarityRequestWithoutASecondTerm() throws Exception {
        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/similarity?first=A"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(endsWith("invalid-argument")));
    }

    @Test
    void rejectsAnIllegalOntologyLabel() throws Exception {
        mockMvc()
                .perform(get("/api/v1/ontologies/not-a-label/root"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(endsWith("invalid-argument")));
    }

    @Test
    void acceptsTheLabelTheParserCanProduceForAFileThatStartsWithAPunctuationCharacter() throws Exception {
        traversal.root = Optional.of(new OntologyTerm("owl:_xOntology", Map.of()));

        mockMvc()
                .perform(get("/api/v1/ontologies/_xOntology/root"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("owl:_xOntology"));
    }

    @Test
    void answersAProblemDetailWhenMaxDepthIsNotANumber() throws Exception {
        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/terms/A/depth?maxDepth=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(endsWith("invalid-argument")));
    }

    @Test
    void answersServiceUnavailableWhenStorageIsDisabled() throws Exception {
        traversal.failure = new StorageUnavailableException("model storage is disabled");

        mockMvc()
                .perform(get("/api/v1/ontologies/SBOOntology/root"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(endsWith("storage-unavailable")));
    }

    /** Keyed by id, so "unknown term" and "no common ancestor" are distinguishable cases. */
    private static final class FakeOntologyTraversal implements OntologyTraversal {
        final Map<String, OntologyTerm> terms = new HashMap<>();
        Optional<OntologyTerm> root = Optional.empty();
        Optional<Integer> depth = Optional.empty();
        Optional<OntologySimilarity> similarity = Optional.empty();
        RuntimeException failure;

        @Override
        public Optional<OntologyTerm> term(String ontology, String id) {
            failIfConfigured();
            return Optional.ofNullable(terms.get(id));
        }

        @Override
        public Optional<OntologyTerm> root(String ontology) {
            failIfConfigured();
            return root;
        }

        @Override
        public Optional<Integer> depth(String ontology, String id, int maxDepth) {
            failIfConfigured();
            return depth;
        }

        @Override
        public Optional<OntologySimilarity> similarity(String ontology, String first, String second, int maxDepth) {
            failIfConfigured();
            return similarity;
        }

        private void failIfConfigured() {
            if (failure != null) {
                throw failure;
            }
        }
    }
}

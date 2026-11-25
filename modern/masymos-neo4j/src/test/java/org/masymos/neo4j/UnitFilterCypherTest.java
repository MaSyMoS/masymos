package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelType;
import org.masymos.core.query.ExactMatch;

/**
 * The unit narrowing of the model query (CML-1, design 4.4): every one of the four model builders
 * gains the same {@code EXISTS} subquery, only when the request names a unit, and the entries
 * combine with AND - inside one constant predicate since QRY-12 (decision 40), so the statement is
 * the same whatever the request holds.
 */
class UnitFilterCypherTest {

    private static final List<List<String>> METRE = List.of(List.of("base:metre"));
    private static final List<List<String>> METRE_AND_SECOND =
            List.of(List.of("base:metre"), List.of("base:second"));
    private static final List<ExactMatch> NO_MATCHES = List.of();
    private static final List<ModelType> NO_KINDS = List.of();

    @Test
    void everyModelQueryCarriesTheUnitPredicateWhenUnitsAreGiven() {
        for (var cypher : List.of(
                Neo4jModelRepository.queryModelsCypher(NO_MATCHES, NO_KINDS, METRE),
                Neo4jModelRepository.queryModelsExactCypher(NO_MATCHES, NO_KINDS, METRE),
                Neo4jModelRepository.groupedModelsCypher(NO_MATCHES, NO_KINDS, METRE),
                Neo4jModelRepository.groupedModelsExactCypher(NO_MATCHES, NO_KINDS, METRE))) {
            assertTrue(cypher.contains("USES_UNIT"), cypher);
            assertTrue(cypher.contains("COMPOSED_OF*0.."), cypher);
            assertTrue(cypher.contains("$unitEntries"), cypher);
            assertTrue(cypher.contains("EXISTS {"), cypher);
        }
    }

    @Test
    void oneEntryAndAThousandEntriesProduceTheSameStatement() {
        var one = Neo4jModelRepository.queryModelsCypher(NO_MATCHES, NO_KINDS, METRE);
        var many = Neo4jModelRepository.queryModelsCypher(NO_MATCHES, NO_KINDS, thousandEntries());

        // The rule in AGENTS.md: the statement is a function of nothing the caller sends.
        assertEquals(one, many);
        assertFalse(one.contains("$unitKeys0"), one);
    }

    @Test
    void entriesAreAndedInsideOneSubquery() {
        var cypher = Neo4jModelRepository.queryModelsCypher(NO_MATCHES, NO_KINDS, METRE_AND_SECOND);

        assertTrue(cypher.contains(" AND all(e IN $unitEntries WHERE EXISTS {"), cypher);
        assertTrue(cypher.contains("WHERE u.key IN e.keys }"), cypher);
        // AND, not OR: the entries are ANDed by the loop over the parameter, and there is exactly
        // one EXISTS block for the whole statement - never one IN over a flattened union.
        assertEquals(cypher.indexOf("EXISTS {"), cypher.lastIndexOf("EXISTS {"), cypher);
    }

    @Test
    void unitParametersKeepsEveryEntryWithItsAlternativesInRequestOrder() {
        var parameters = Neo4jModelRepository.unitParameters(
                List.of(List.of("base:metre", "unit:metre^1"), List.of("base:second")));

        assertEquals(Set.of("unitEntries"), parameters.keySet());
        @SuppressWarnings("unchecked")
        var entries = (List<Map<String, Object>>) parameters.get("unitEntries");
        assertEquals(2, entries.size());
        // OR within one entry survives the move: both spellings stay in that entry's key list.
        assertEquals(List.of("base:metre", "unit:metre^1"), entries.get(0).get("keys"));
        assertEquals(List.of("base:second"), entries.get(1).get("keys"));
        assertEquals(Map.of(), Neo4jModelRepository.unitParameters(List.of()));
        assertEquals(Map.of(), Neo4jModelRepository.unitParameters(null));
    }

    private static List<List<String>> thousandEntries() {
        var entries = new ArrayList<List<String>>();
        for (var index = 0; index < 1000; index++) {
            entries.add(List.of("base:unit" + index));
        }
        return entries;
    }

    @Test
    void aQueryWithoutUnitsKeepsTheCypherItHadBefore() {
        assertFalse(Neo4jModelRepository.queryModelsCypher(NO_MATCHES, NO_KINDS).contains("USES_UNIT"));
        assertFalse(Neo4jModelRepository.queryModelsExactCypher(NO_MATCHES, NO_KINDS).contains("unitKeys"));
    }

    @Test
    void theUnitPredicateIsAnAndContinuationLikeTheKindPredicate() {
        var cypher = Neo4jModelRepository.queryModelsExactCypher(NO_MATCHES, List.of(ModelType.SBML), METRE);

        assertTrue(cypher.contains(" AND all(e IN $unitEntries"), cypher);
    }
}

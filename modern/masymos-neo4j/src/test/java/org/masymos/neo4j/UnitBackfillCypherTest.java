package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * STO-6: the backfill's Cypher writes the unit layer of an existing document and nothing else. The
 * identity of every write is pinned here, because that is what makes a second run a no-op - and the
 * absence of a {@code CREATE} or an owner stamp is what makes it safe on a live graph.
 */
class UnitBackfillCypherTest {

    @Test
    void unitsAreMergedOnTheCanonicalKeyAndNeverOwned() {
        var cypher = UnitBackfillCypher.WRITE_UNITS;

        assertTrue(cypher.contains("MERGE (u:UNIT {key: unit.key})"), cypher);
        assertTrue(cypher.contains("SET u += unit.properties"), cypher);
        assertFalse(cypher.contains("ownerUid"), "a shared unit has no owning document: " + cypher);
        assertFalse(cypher.contains("CREATE"), "a backfill must be re-runnable: " + cypher);
    }

    @Test
    void compositionEdgesAreMergedOnEndpointsPlusExponentAndMultiplier() {
        var cypher = UnitBackfillCypher.WRITE_COMPOSITIONS;

        assertTrue(
                cypher.contains("MERGE (a)-[r:COMPOSED_OF {exponent: edge.exponent, multiplier: edge.multiplier}]->(b)"),
                cypher);
        assertFalse(cypher.contains("CREATE"), cypher);
    }

    @Test
    void usesIsMergedFromTheDocumentsOwnModelNode() {
        var cypher = UnitBackfillCypher.WRITE_USES;

        assertTrue(cypher.contains("MATCH (d:Document {uid: $uid})-[:HAS_MODEL]->(m:Model)"), cypher);
        assertTrue(cypher.contains("MERGE (m)-[r:USES_UNIT]->(u)"), cypher);
        assertFalse(cypher.contains("CREATE"), cypher);
    }

    @Test
    void hasUnitStartsFromTheEntityLabelAndIdScopedToTheDocument() {
        var cypher = UnitBackfillCypher.writeHasUnits("CELLML_VARIABLE");

        assertTrue(cypher.contains("MATCH (e:`CELLML_VARIABLE` {ownerUid: $uid, id: edge.id})"), cypher);
        assertTrue(cypher.contains("MERGE (e)-[r:HAS_UNIT]->(u)"), cypher);
        assertTrue(cypher.contains("SET r += edge.properties"), "declaredName travels with the edge: " + cypher);
        assertFalse(cypher.contains("CREATE"), cypher);
    }

    @Test
    void theReadQueriesOnlyCountAndNeverWrite() {
        for (var cypher : new String[] {
            UnitBackfillCypher.DOCUMENT_COUNT,
            UnitBackfillCypher.STORED_DOCUMENTS,
            UnitBackfillCypher.ALREADY_COMPLETE,
            UnitBackfillCypher.MISSING_UNITS,
            UnitBackfillCypher.MISSING_COMPOSITIONS,
            UnitBackfillCypher.MISSING_USES,
            UnitBackfillCypher.missingHasUnits("SBML_SPECIES"),
            UnitBackfillCypher.resolvableHasUnits("SBML_SPECIES")
        }) {
            assertFalse(cypher.contains("MERGE") || cypher.contains("CREATE"), "a counting query must not write: " + cypher);
        }
    }

    @Test
    void aDocumentIsCompleteOnlyWhenItHasAUnitEdge() {
        var cypher = UnitBackfillCypher.ALREADY_COMPLETE;

        assertTrue(cypher.contains("EXISTS { MATCH (d)-[:HAS_MODEL]->(:Model)-[:USES_UNIT]->(:UNIT) }"), cypher);
    }
}

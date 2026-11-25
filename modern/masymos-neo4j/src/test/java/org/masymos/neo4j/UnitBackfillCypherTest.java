package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aCompositionEdgeIsMergedOnItsEndpoints() {
        var cypher = UnitBackfillCypher.WRITE_COMPOSITIONS;

        assertTrue(cypher.contains("MERGE (a)-[r:COMPOSED_OF]->(b)"), cypher);
        assertFalse(
                cypher.contains("MERGE (a)-[r:COMPOSED_OF {"),
                "exponent and multiplier are properties, not identity: " + cypher);
        assertTrue(
                cypher.contains("SET r.exponent = edge.exponent, r.multiplier = edge.multiplier"),
                "the surviving definition still carries its values: " + cypher);
        assertFalse(cypher.contains("CREATE"), cypher);
        assertFalse(
                UnitBackfillCypher.MISSING_COMPOSITIONS.contains("{exponent"),
                "the presence check must not key on properties the merge ignores: "
                        + UnitBackfillCypher.MISSING_COMPOSITIONS);
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
            UnitBackfillCypher.STORED_DOCUMENT_COUNT,
            UnitBackfillCypher.STORED_DOCUMENTS_AFTER_COUNT,
            UnitBackfillCypher.STORED_DOCUMENTS,
            UnitBackfillCypher.STORED_DOCUMENTS_LIMITED,
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

    /**
     * T-018: the bound is a parameter, not a rendered clause, so a bounded run of any corpus is one
     * statement of one shape - and the unbounded statement is the same text without the clause.
     */
    @Test
    void theBoundedScanTakesItsLimitAsAParameter() {
        var limited = UnitBackfillCypher.STORED_DOCUMENTS_LIMITED;

        assertEquals(UnitBackfillCypher.STORED_DOCUMENTS + "LIMIT $limit\n", limited);
        assertTrue(limited.endsWith("LIMIT $limit\n"), limited);
        assertTrue(UnitBackfillCypher.STORED_DOCUMENTS.endsWith("ORDER BY uid\n"), "the order makes two runs agree");
    }

    /**
     * {@code C04-2}: a page boundary is a position in the population, so it is a parameter of the same
     * statement - not an offset, which shifts by one whenever a document is deleted (STO-4) and makes
     * a sweep skip a document it was meant to read.
     */
    @Test
    void theScanTakesItsPagePositionAsAParameterAndNeverSkips() {
        var scan = UnitBackfillCypher.STORED_DOCUMENTS;

        assertTrue(scan.contains("AND ($after IS NULL OR d.uid > $after)"), scan);
        assertFalse(scan.contains("SKIP"), "an offset moves under a delete; a keyset does not: " + scan);
        assertTrue(scan.endsWith("ORDER BY uid\n"), "the position is the same order the scan returns: " + scan);
    }

    @Test
    void theRemainingCountBoundsTheSamePopulationAfterTheSamePosition() {
        var count = UnitBackfillCypher.STORED_DOCUMENTS_AFTER_COUNT;

        assertTrue(count.contains("MATCH (d:Document)"));
        assertTrue(count.contains("WHERE d.xmlDoc IS NOT NULL"), count);
        assertTrue(
                count.contains("AND ($after IS NULL OR d.uid > $after)"),
                "the remainder must be counted from the page's last uid, or documentsNotScanned lies: "
                        + count);
        assertTrue(count.contains("RETURN count(d) AS count"));
    }

    @Test
    void theStoredDocumentCountBoundsTheSamePopulationTheScanReads() {
        var count = UnitBackfillCypher.STORED_DOCUMENT_COUNT;

        assertTrue(count.contains("MATCH (d:Document)"));
        assertTrue(count.contains("WHERE d.xmlDoc IS NOT NULL"),
                "the count and the scan must select the same documents, or documentsNotScanned lies: " + count);
        assertTrue(count.contains("RETURN count(d) AS count"));
    }

    @Test
    void aDocumentIsCompleteOnlyWhenItHasAUnitEdge() {
        var cypher = UnitBackfillCypher.ALREADY_COMPLETE;

        assertTrue(cypher.contains("EXISTS { MATCH (d)-[:HAS_MODEL]->(:Model)-[:USES_UNIT]->(:UNIT) }"), cypher);
    }
}

package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.fusion.ModelRows;

/**
 * Introspection of the row lookup's Cypher and the empty-input short circuit. The query itself is
 * exercised against a container in {@code Neo4jContainersIT}.
 */
class Neo4jModelRowLookupTest {
    private static final Pattern MANDATORY_HAS_MODEL =
            Pattern.compile("(?m)^[ \\t]*MATCH \\(document:Document\\)-\\[:HAS_MODEL\\]->\\(node\\)");

    @Test
    void matchesModelsOnTheIndexedExactSourceUri() {
        var cypher = Neo4jModelRowLookup.lookupCypher();

        assertTrue(cypher.contains("MATCH (node:Model)"), cypher);
        assertTrue(cypher.contains("node.sourceUriExact IN $uris"), cypher);
    }

    @Test
    void keepsMetadataOnlyNodesThroughAnOptionalHasModelMatch() {
        var cypher = Neo4jModelRowLookup.lookupCypher();

        assertTrue(cypher.contains("OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)"), cypher);
        assertTrue(cypher.contains("coalesce(document, node)"), cypher);
        assertFalse(MANDATORY_HAS_MODEL.matcher(cypher).find(), cypher);
    }

    @Test
    void returnsAnEmptyModelRowsForNoUrisWithoutADriver() {
        var lookup = new Neo4jModelRowLookup(null, "neo4j");

        assertEquals(ModelRows.empty(), lookup.byDocumentUris(List.of()));
    }

    /**
     * {@code REL-007}. A text anchor only - the behaviour (how many rows a real store answers) is
     * covered against a container in {@code Neo4jContainersIT}; what is pinned here is that the bound
     * is a constant parameter and not a rendered number, so the statement text cannot grow with the
     * request.
     */
    @Test
    void lookupCypherCarriesTheRowLimit() {
        var cypher = Neo4jModelRowLookup.lookupCypher();

        assertTrue(cypher.contains("LIMIT $limit"), cypher);
        assertTrue(cypher.trim().endsWith("LIMIT $limit"), "the limit is the last clause: " + cypher);
        assertTrue(cypher.contains("ORDER BY uid"),
                "ORDER BY uid is also the truncation order, so the cut is stable: " + cypher);
        assertEquals(2000, Neo4jModelRowLookup.MAX_LOOKUP_ROWS);
    }

    /**
     * {@code C03-2}. A cut is only visible because the statement asks for one row more than it may
     * return: with {@code LIMIT 2000} an answer of 2 000 rows means "exactly 2 000 matched" and "the
     * tail was cut" at the same time, and no caller could tell them apart.
     */
    @Test
    void asksForOneRowMoreThanItReturnsSoACutIsVisible() {
        assertEquals(Neo4jModelRowLookup.MAX_LOOKUP_ROWS + 1, Neo4jModelRowLookup.requestedRows());
    }

    /**
     * {@code C03-2}. The probe row is the whole mechanism, and the boundary is the case worth pinning:
     * exactly the bound is complete, one more is a cut.
     */
    @Test
    void treatsTheProbeRowAsTheOnlySignOfACut() {
        assertFalse(Neo4jModelRowLookup.isTruncated(0));
        assertFalse(Neo4jModelRowLookup.isTruncated(Neo4jModelRowLookup.MAX_LOOKUP_ROWS - 1),
                "a short answer cut nothing");
        assertFalse(Neo4jModelRowLookup.isTruncated(Neo4jModelRowLookup.MAX_LOOKUP_ROWS),
                "exactly the bound is a complete answer, not a cut");
        assertTrue(Neo4jModelRowLookup.isTruncated(Neo4jModelRowLookup.requestedRows()),
                "the probe row answered, so the store held more than the bound");
    }
}

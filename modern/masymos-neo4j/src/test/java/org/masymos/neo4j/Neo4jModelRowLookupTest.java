package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

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
    void returnsAnEmptyListForNoUrisWithoutADriver() {
        var lookup = new Neo4jModelRowLookup(null, "neo4j");

        assertEquals(List.of(), lookup.byDocumentUris(List.of()));
    }
}

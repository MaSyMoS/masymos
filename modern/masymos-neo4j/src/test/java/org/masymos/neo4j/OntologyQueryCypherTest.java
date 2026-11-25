package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OntologyQueryCypherTest {
    @Test
    void preparesNodeAndRootLookupCypher() {
        var nodeById = OntologyQueryCypher.nodeById("SBOOntology", "SBO_0000001");
        var root = OntologyQueryCypher.root("SBOOntology");

        assertTrue(nodeById.cypher().contains("MATCH (term:SBOOntology)"));
        assertEquals("SBO_0000001", nodeById.parameters().get("id"));
        assertEquals("owl:SBOOntology", root.parameters().get("id"));
    }

    @Test
    void preparesPathAndLcaQueries() {
        var path = OntologyQueryCypher.shortestPathLength("SBOOntology", "SBO_0000001", "SBO_0000002", 15);
        var depth = OntologyQueryCypher.depth("SBOOntology", "SBO_0000002", 15);
        var lca = OntologyQueryCypher.lowestCommonAncestor("SBOOntology", "SBO_0000001", "SBO_0000002", 15);

        assertTrue(path.cypher().contains("shortestPath"));
        assertTrue(path.cypher().contains("[:isA*..15]"));
        assertTrue(depth.cypher().contains("->(root)"));
        assertTrue(lca.cypher().contains("ancestor"));
        assertTrue(
                lca.cypher().matches("(?s).*\\[:isA\\*0\\.\\.15\\].*\\[:isA\\*0\\.\\.15\\].*"),
                "both legs must carry the caller's bound and allow zero hops, so a term can be its own"
                        + " end of the relationship and a parent is not replaced by the root: " + lca.cypher());
    }

    @Test
    void preservesLegacySimilarityFormula() {
        assertEquals(0.0d, OntologyQueryCypher.similarity(1, 0));
        assertTrue(OntologyQueryCypher.similarity(1, 3) > OntologyQueryCypher.similarity(5, 1));
    }

    @Test
    void ordersCommonAncestorsSoTheLowestIsReturned() {
        var lca = OntologyQueryCypher.lowestCommonAncestor("SBOOntology", "SBO_0000001", "SBO_0000002", 15);

        assertTrue(lca.cypher().contains("MATCH path ="));
        assertTrue(lca.cypher().contains("ORDER BY length(path)"));
        assertTrue(lca.cypher().contains("RETURN ancestor.id AS id"));
    }

    @Test
    void rejectsUnsafeLabelsAndInvalidDepths() {
        assertThrows(IllegalArgumentException.class, () -> OntologyQueryCypher.nodeById("SBO Ontology", "x"));
        assertThrows(IllegalArgumentException.class, () -> OntologyQueryCypher.depth("SBOOntology", "x", 0));
    }

    @Test
    void acceptsTheLabelTheParserCanProduceForAFileThatStartsWithAPunctuationCharacter() {
        // sanitizeLabel turns "-x.owl" into "_xOntology" and accepts it, so the traversal must too.
        var nodeById = OntologyQueryCypher.nodeById("_xOntology", "A");

        assertTrue(nodeById.cypher().contains("MATCH (term:_xOntology)"));
    }
}

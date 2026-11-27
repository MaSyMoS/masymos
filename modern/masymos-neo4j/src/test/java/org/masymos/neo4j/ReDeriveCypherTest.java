package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Shape guards for the read-only re-derive scan ({@code OPN-7}). */
class ReDeriveCypherTest {
    @Test
    void countsOwnedNodesAndTheirMissingDerivedKeyInOneRead() {
        var cypher = ReDeriveCypher.ownedNodeState(List.of("Model", "SBML_MODEL"));

        assertTrue(cypher.contains("MATCH (n:Model:SBML_MODEL)"), cypher);
        assertTrue(cypher.contains("n.ownerUid = $uid"), cypher);
        assertTrue(cypher.contains("count(CASE WHEN n.derivedKey IS NULL THEN 1 END)"), cypher);
        assertFalse(cypher.contains("CREATE"), "the scan must not write: " + cypher);
        assertFalse(cypher.contains("MERGE"), "the scan must not write: " + cypher);
        assertFalse(cypher.contains("DELETE"), "the scan must not write: " + cypher);
    }

    /**
     * One statement per label set, never one per node: the label chain is the writer's bounded
     * vocabulary, which is what keeps the scan's statement text a function of the schema and not of
     * the document.
     */
    @Test
    void splicesTheLabelChainFromTheVocabularyNotFromTheDocument() {
        assertEquals(":ANNOTATION", ReDeriveCypher.chain(List.of("ANNOTATION")));
        assertEquals(":Model:CELLML_MODEL", ReDeriveCypher.chain(List.of("Model", "CELLML_MODEL")));
    }
}

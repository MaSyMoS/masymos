package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelDeletionCypherTest {
    @Test
    void relinksPredecessorsAndSuccessorsAroundTheDeletedVersion() {
        var cypher = ModelDeletionCypher.relinkVersionChainCypher();

        assertTrue(cypher.contains("MATCH (d:Document {uid: $uid})"));
        assertTrue(cypher.contains("MATCH (predecessor:Document)-[:HAS_SUCCESSOR]->(d)"));
        assertTrue(cypher.contains("MATCH (d)-[:HAS_SUCCESSOR]->(successor:Document)"));
        assertTrue(cypher.contains("MERGE (predecessor)-[:HAS_SUCCESSOR]->(successor)"));
        assertTrue(cypher.contains("MERGE (successor)-[:HAS_PREDECESSOR]->(predecessor)"));
    }

    @Test
    void deletesOnlyTheNodesOwnedByTheDocument() {
        var cypher = ModelDeletionCypher.deleteOwnedGraphCypher();

        assertTrue(cypher.contains("WHERE n.ownerUid = d.uid AND n <> d"));
        assertTrue(cypher.contains("FOREACH (node IN owned | DETACH DELETE node)"));
        assertTrue(cypher.contains("DETACH DELETE d"));
        assertTrue(cypher.contains("RETURN 1 + size(owned) AS nodes"));
        // A traversal from the model would cross into a SED-ML document's graph through the stored
        // link relationships, so deletion must never be expressed as a traversal.
        assertFalse(cypher.contains("[*0..]"));
        assertFalse(cypher.contains("HAS_SUCCESSOR"));
    }

    @Test
    void purgesTheThreeLegacyOrphanLabels() {
        assertEquals(List.of("RESOURCE", "PUBLICATION", "PERSON"), ModelDeletionCypher.ORPHAN_LABELS);
        for (var label : ModelDeletionCypher.ORPHAN_LABELS) {
            var cypher = ModelDeletionCypher.purgeOrphansCypher(label);
            assertTrue(cypher.contains("MATCH (n:" + label + ") WHERE NOT (n)--()"));
            assertTrue(cypher.contains("RETURN size(nodes) AS purged"));
        }
    }

    @Test
    void deletesEveryVersionOfAFileInASingleScan() {
        var cypher = ModelDeletionCypher.deleteOwnedGraphsCypher();

        assertTrue(cypher.contains("MATCH (d:Document {fileId: $fileId})"));
        assertTrue(cypher.contains("WHERE n.ownerUid IN uids AND NOT (n IN documents)"));
        assertTrue(cypher.contains("RETURN size(documents) AS documents, size(documents) + size(owned) AS nodes"));
        assertFalse(cypher.contains("[*0..]"));
    }

    @Test
    void refusesToBuildAPurgeForAnUnknownLabel() {
        assertThrows(IllegalArgumentException.class, () -> ModelDeletionCypher.purgeOrphansCypher("Document"));
    }

    @Test
    void theUnitPurgeKeepsWhatSomeEntityStillUses() {
        var cypher = ModelDeletionCypher.purgeUnreachableUnitsCypher();

        assertTrue(cypher.contains("MATCH (x:UNIT)"), cypher);
        assertTrue(cypher.contains("WHERE NOT EXISTS {"), cypher);
        assertTrue(cypher.contains("WHERE ()-[:HAS_UNIT]->(used) OR (:Model)-[:USES_UNIT]->(used)"), cypher);
        assertTrue(cypher.contains("COMPOSED_OF*0.."), cypher);
        assertTrue(cypher.contains("DETACH DELETE x"), cypher);
    }
}

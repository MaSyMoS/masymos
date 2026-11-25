package org.masymos.neo4j;

import java.util.List;

/**
 * The Cypher of the model lifecycle delete (STO-4).
 *
 * <p>Deletion is ownership-scoped, not traversal-scoped: every node written for a document carries
 * {@code ownerUid}, and the delete removes exactly the nodes carrying it. A traversal from the
 * model would follow the stored SED-ML link relationships into another document's graph and delete
 * it; legacy had to cut those edges by hand for the same reason ({@code ModelDeleter.java:378-401}).
 *
 * <p>The chain is relinked before the version node is removed, and the three annotation labels are
 * purged afterwards only when nothing references them any more ({@code ModelDeleter.java:250-291}).
 */
final class ModelDeletionCypher {
    /** The labels legacy purged after a delete; nothing else is purged. */
    static final List<String> ORPHAN_LABELS = List.of("RESOURCE", "PUBLICATION", "PERSON");

    private ModelDeletionCypher() {
    }

    /**
     * Reconnects every predecessor of the deleted version to every successor, so deleting a middle
     * version leaves one valid chain instead of cutting it ({@code ModelDeleter.java:363-370}).
     */
    static String relinkVersionChainCypher() {
        return """
                MATCH (d:Document {uid: $uid})
                MATCH (predecessor:Document)-[:HAS_SUCCESSOR]->(d)
                MATCH (d)-[:HAS_SUCCESSOR]->(successor:Document)
                MERGE (predecessor)-[:HAS_SUCCESSOR]->(successor)
                MERGE (successor)-[:HAS_PREDECESSOR]->(predecessor)
                """;
    }

    /**
     * Deletes the document and every node it owns. Nodes shared with another document (a SED-ML
     * link target, an archive node) are not owned by it, so they survive; only the relationships
     * that pointed at the deleted nodes disappear with {@code DETACH DELETE}.
     */
    static String deleteOwnedGraphCypher() {
        return """
                MATCH (d:Document {uid: $uid})
                OPTIONAL MATCH (n)
                WHERE n.ownerUid = d.uid AND n <> d
                WITH d, [node IN collect(DISTINCT n) WHERE node IS NOT NULL] AS owned
                FOREACH (node IN owned | DETACH DELETE node)
                DETACH DELETE d
                RETURN 1 + size(owned) AS nodes
                """;
    }

    /**
     * Deletes every version of one file in a single scan. The per-document query would scan the
     * store once per version; this one collects the owners first and removes their graphs in one
     * pass.
     */
    static String deleteOwnedGraphsCypher() {
        return """
                MATCH (d:Document {fileId: $fileId})
                WITH collect(d) AS documents
                WITH documents, [doc IN documents | doc.uid] AS uids
                OPTIONAL MATCH (n)
                WHERE n.ownerUid IN uids AND NOT (n IN documents)
                WITH documents, [node IN collect(DISTINCT n) WHERE node IS NOT NULL] AS owned
                FOREACH (node IN owned | DETACH DELETE node)
                FOREACH (doc IN documents | DETACH DELETE doc)
                RETURN size(documents) AS documents, size(documents) + size(owned) AS nodes
                """;
    }

    /** Removes the nodes of one annotation label that no longer have any relationship at all. */
    static String purgeOrphansCypher(String label) {
        if (!ORPHAN_LABELS.contains(label)) {
            throw new IllegalArgumentException("label is not purged after a delete: " + label);
        }
        return "MATCH (n:" + label + ") WHERE NOT (n)--()\n"
                + "WITH collect(n) AS nodes\n"
                + "FOREACH (node IN nodes | DETACH DELETE node)\n"
                + "RETURN size(nodes) AS purged\n";
    }

    /**
     * Removes the unit nodes nothing references any more (CML-1, design 4.2).
     *
     * <p>Units are shared, so "orphan" cannot mean "has no relationship": a unit still referenced by
     * a live entity has to survive, and so has everything that unit is composed of. The roots are
     * therefore the {@code HAS_UNIT} edges of the entities that remain - the deleted document's own
     * edges are gone by now - plus the {@code USES_UNIT} edges of the models that remain, because a
     * unit a model declares and never uses still counts as used (design 4.4). Neither root can
     * resurrect a deleted model's units: its model node, and with it every {@code USES_UNIT} edge,
     * was deleted before this query runs.
     *
     * <p>The reachability test is a per-unit {@code NOT EXISTS} rather than a collected root set,
     * because a collected set is empty on the last delete and an empty set matches no rows at all -
     * the query then keeps every unit for ever. Written this way, "no roots left" means "every unit
     * is an orphan", which is exactly when the last model goes.
     *
     * <p>{@code COMPOSED_OF*0..} keeps the whole definition closure of a live root in one pass: a
     * composed unit can outlive its dependents for one pass, and iterating would need a bound.
     */
    static String purgeUnreachableUnitsCypher() {
        return """
                MATCH (x:UNIT)
                WHERE NOT EXISTS {
                    MATCH (used:UNIT)
                    WHERE ()-[:HAS_UNIT]->(used) OR (:Model)-[:USES_UNIT]->(used)
                    MATCH (used)-[:COMPOSED_OF*0..]->(x)
                }
                WITH collect(x) AS orphans
                FOREACH (x IN orphans | DETACH DELETE x)
                RETURN size(orphans) AS purged
                """;
    }
}

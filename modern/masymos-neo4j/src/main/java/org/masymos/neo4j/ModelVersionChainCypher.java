package org.masymos.neo4j;

/**
 * Links a freshly written document version into the version chain of its model file.
 *
 * <p>Legacy MaSyMoS created both directions for every resolved predecessor
 * ({@code ModelInserter.java:82-83}) and never validated that a predecessor belongs to the same
 * file, so a file could branch; "latest" was defined only as "no outgoing {@code HAS_SUCCESSOR}"
 * ({@code ModelLookup.java:76}). This class keeps that topology instead of inventing a stricter
 * one: every current tail of the file becomes a predecessor of the new version.
 *
 * <p>It runs in the same transaction as the graph write, so a document never exists without its
 * chain links.
 */
final class ModelVersionChainCypher {
    private ModelVersionChainCypher() {
    }

    static String linkPredecessorsCypher() {
        return """
                MATCH (document:Document {uid: $uid})
                OPTIONAL MATCH (predecessor:Document {fileId: $fileId})
                WHERE predecessor.uid <> $uid AND NOT (predecessor)-[:HAS_SUCCESSOR]->()
                WITH document, collect(predecessor) AS predecessors
                FOREACH (predecessor IN predecessors |
                  CREATE (predecessor)-[:HAS_SUCCESSOR]->(document)
                  CREATE (document)-[:HAS_PREDECESSOR]->(predecessor)
                )
                """;
    }
}

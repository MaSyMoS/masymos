package org.masymos.neo4j;

import java.util.Map;

final class OntologyQueryCypher {
    private OntologyQueryCypher() {
    }

    static PreparedGraphWrite nodeById(String ontologyLabel, String id) {
        return new PreparedGraphWrite("""
                MATCH (term:%s)
                WHERE term.id = $id
                RETURN term
                LIMIT 1
                """.formatted(label(ontologyLabel)), Map.of("id", id));
    }

    static PreparedGraphWrite root(String ontologyLabel) {
        return nodeById(ontologyLabel, "owl:" + ontologyLabel);
    }

    static PreparedGraphWrite shortestPathLength(String ontologyLabel, String firstId, String secondId, int maxDepth) {
        return new PreparedGraphWrite("""
                MATCH (first:%s {id: $firstId}), (second:%s {id: $secondId})
                MATCH path = shortestPath((first)-[:isA*..%d]-(second))
                RETURN length(path) AS pathLength
                """.formatted(label(ontologyLabel), label(ontologyLabel), positiveDepth(maxDepth)), Map.of(
                "firstId", firstId,
                "secondId", secondId
        ));
    }

    static PreparedGraphWrite depth(String ontologyLabel, String id, int maxDepth) {
        return new PreparedGraphWrite("""
                MATCH (term:%s {id: $id}), (root:%s {id: $rootId})
                MATCH path = shortestPath((term)-[:isA*..%d]->(root))
                RETURN length(path) AS depth
                """.formatted(label(ontologyLabel), label(ontologyLabel), positiveDepth(maxDepth)), Map.of(
                "id", id,
                "rootId", "owl:" + ontologyLabel
        ));
    }

    static PreparedGraphWrite lowestCommonAncestor(
            String ontologyLabel, String firstId, String secondId, int maxDepth) {
        return new PreparedGraphWrite("""
                MATCH (first:%s {id: $firstId}), (second:%s {id: $secondId})
                MATCH path = (first)-[:isA*0..%d]->(ancestor)<-[:isA*0..%d]-(second)
                RETURN ancestor.id AS id
                ORDER BY length(path)
                LIMIT 1
                """.formatted(
                        label(ontologyLabel),
                        label(ontologyLabel),
                        positiveDepth(maxDepth),
                        positiveDepth(maxDepth)), Map.of(
                "firstId", firstId,
                "secondId", secondId
        ));
    }

    static double similarity(int shortestPathLength, int lowestCommonAncestorDepth) {
        var alpha = 0.2d;
        var beta = 0.6d;
        return Math.exp(-1d * alpha * shortestPathLength)
                * (Math.exp(beta * lowestCommonAncestorDepth) - Math.exp(-1d * beta * lowestCommonAncestorDepth))
                / (Math.exp(beta * lowestCommonAncestorDepth) + Math.exp(-1d * beta * lowestCommonAncestorDepth));
    }

    private static String label(String ontologyLabel) {
        if (!ontologyLabel.matches("[A-Za-z_][A-Za-z0-9_]*Ontology")) {
            throw new IllegalArgumentException("Unsupported ontology label: " + ontologyLabel);
        }
        return ontologyLabel;
    }

    private static int positiveDepth(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be positive");
        }
        return maxDepth;
    }
}

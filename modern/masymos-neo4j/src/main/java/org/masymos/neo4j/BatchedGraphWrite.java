package org.masymos.neo4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One document write as a fixed number of statements whose size never depends on the document.
 *
 * <p>STO-7: the previous shape put every element into a single Cypher string, two clauses each, so
 * a 553-node ontology document became one statement of 5,530 clauses and the server exhausted its
 * heap while planning it (51N36, 2026-11-18). A statement is now either a fixed prologue (the
 * {@code GlobalUniqueId} counter and the document node) or an {@code UNWIND $rows AS row} block of at
 * most {@link #BATCH_SIZE} rows, so what grows with the document is the number of rows, never the
 * number of clauses.
 *
 * <p>All statements still run in the one transaction {@code Neo4jExtractedModelGraphRepository.insert}
 * opens: a document is atomic, and the version chain is still linked before the commit.
 */
record BatchedGraphWrite(
        Statement document,
        List<Statement> nodes,
        List<RelationshipBatch> relationships
) {
    /**
     * Rows per {@code UNWIND} block. Small enough that a row's properties stay bounded (only the
     * document node carries a stored {@code xmlDoc}, and it has its own statement), large enough that
     * a 5,000-species model needs tens of statements rather than thousands.
     */
    static final int BATCH_SIZE = 250;

    /** One Cypher statement with the parameters the driver needs to run it. */
    record Statement(String cypher, Map<String, Object> parameters) {
    }

    /**
     * Relationships of one type. The type is part of the Cypher text rather than a parameter, so a
     * write needs one block per relationship type the document uses - a schema fact, not a size fact.
     * {@code COMPOSED_OF} is merged on its identity triple instead of created, because two definitions
     * of the same unit pair differ only in exponent and multiplier (CML-1).
     */
    record RelationshipBatch(String type, List<Row> rows) {
        boolean mergesOnIdentityTriple() {
            return type.equals("COMPOSED_OF");
        }
    }

    /** One relationship of a batch, still addressed by graph key: the element ids exist only at run time. */
    record Row(String startKey, String endKey, Map<String, Object> properties) {
    }

    /**
     * The relationship statements for this write, with every endpoint key resolved to the element id
     * the node statements reported. A key without an element id is an internal error: the node write
     * either created it or the graph never named it, and {@code validateGraph} rejects the latter.
     */
    List<Statement> relationshipStatements(Map<String, String> elementIdsByKey) {
        var statements = new ArrayList<Statement>();
        for (var batch : relationships) {
            for (var chunk : chunks(batch.rows())) {
                var rows = new ArrayList<Map<String, Object>>(chunk.size());
                for (var row : chunk) {
                    rows.add(Map.of(
                            "start", elementId(elementIdsByKey, row.startKey()),
                            "end", elementId(elementIdsByKey, row.endKey()),
                            "properties", row.properties()));
                }
                statements.add(new Statement(
                        relationshipCypher(batch.type(), batch.mergesOnIdentityTriple()),
                        Map.of("rows", List.copyOf(rows))));
            }
        }
        return List.copyOf(statements);
    }

    private static String elementId(Map<String, String> elementIdsByKey, String key) {
        var elementId = elementIdsByKey.get(key);
        if (elementId == null) {
            throw new IllegalStateException("the write created no node for the graph key " + key);
        }
        return elementId;
    }

    /**
     * Endpoints are addressed by {@code elementId}, which Neo4j serves with {@code NodeByElementIdSeek}
     * (measured on 2026.05 with {@code EXPLAIN}): a constant-cost lookup instead of a scan per row.
     */
    private static String relationshipCypher(String type, boolean mergesOnIdentityTriple) {
        var write = mergesOnIdentityTriple
                ? "MERGE (start)-[relationship:" + type
                        + " {exponent: row.properties.exponent, multiplier: row.properties.multiplier}]->(end)\n"
                : "CREATE (start)-[relationship:" + type + "]->(end)\n";
        return "UNWIND $rows AS row\n"
                + "MATCH (start) WHERE elementId(start) = row.start\n"
                + "MATCH (end) WHERE elementId(end) = row.end\n"
                + write
                + "SET relationship += row.properties\n";
    }

    static <T> List<List<T>> chunks(List<T> values) {
        var chunks = new ArrayList<List<T>>();
        for (var index = 0; index < values.size(); index += BATCH_SIZE) {
            chunks.add(List.copyOf(values.subList(index, Math.min(values.size(), index + BATCH_SIZE))));
        }
        return chunks;
    }
}

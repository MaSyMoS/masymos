package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The model full-text index generation, pinned as text the way the other Cypher tests are.
 *
 * <p>CML-3 adds the aggregated CellML keywords to the searchable field list, which needs a new
 * generation: Neo4j cannot widen a FULLTEXT index in place, so V5 replaces V4 and the initializer
 * drops every older generation so no query can be served from a stale field list.
 */
class Neo4jSchemaInitializerTest {

    private static List<String> statements() {
        return Neo4jSchemaInitializer.statements();
    }

    @Test
    void theCreatedStatementNamesTheV5Generation() {
        assertTrue(
                statements().stream().anyMatch(statement -> statement.contains("CREATE FULLTEXT INDEX modelFulltextV5")),
                statements().toString());
        assertTrue(
                statements().stream().noneMatch(statement -> statement.contains("CREATE FULLTEXT INDEX modelFulltextV4")),
                statements().toString());
    }

    @Test
    void theV5StatementIndexesTheModelKeywords() {
        var create = statements().stream()
                .filter(statement -> statement.contains("CREATE FULLTEXT INDEX modelFulltextV5"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no modelFulltextV5 statement in:\n" + statements()));

        assertTrue(create.contains("FOR (m:Model)"), create);
        assertTrue(create.contains("ON EACH ["), create);
        assertTrue(create.contains("m.keywords"), create);
    }

    @Test
    void theDropListRetiresEveryOlderGeneration() {
        for (var generation : List.of("modelFulltextV4", "modelFulltextV3", "modelFulltextV2")) {
            assertTrue(
                    statements().contains("DROP INDEX " + generation + " IF EXISTS"),
                    generation + " missing from:\n" + statements());
        }
    }
}

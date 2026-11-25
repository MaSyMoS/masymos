package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The model full-text index generation, pinned as text the way the other Cypher tests are.
 *
 * <p>A searchable field is added by a new generation, because Neo4j cannot widen a FULLTEXT index in
 * place: CML-3 added {@code m.keywords} as V5 and FBC-2 adds {@code m.geneProduct} as V6, and the
 * initializer drops every older generation so no query can be served from a stale field list.
 */
class Neo4jSchemaInitializerTest {

    private static List<String> statements() {
        return Neo4jSchemaInitializer.statements();
    }

    @Test
    void theCreatedStatementNamesTheV6Generation() {
        assertTrue(
                statements().stream().anyMatch(statement -> statement.contains("CREATE FULLTEXT INDEX modelFulltextV6")),
                statements().toString());
        assertTrue(
                statements().stream().noneMatch(statement -> statement.contains("CREATE FULLTEXT INDEX modelFulltextV5")),
                statements().toString());
    }

    @Test
    void theV6StatementIndexesTheModelKeywordsAndGeneProducts() {
        var create = statements().stream()
                .filter(statement -> statement.contains("CREATE FULLTEXT INDEX modelFulltextV6"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no modelFulltextV6 statement in:\n" + statements()));

        assertTrue(create.contains("FOR (m:Model)"), create);
        assertTrue(create.contains("ON EACH ["), create);
        assertTrue(create.contains("m.keywords"), create);
        assertTrue(create.contains("m.geneProduct"), create);
    }

    @Test
    void theDropListRetiresEveryOlderGeneration() {
        for (var generation : List.of("modelFulltextV5", "modelFulltextV4", "modelFulltextV3", "modelFulltextV2")) {
            assertTrue(
                    statements().contains("DROP INDEX " + generation + " IF EXISTS"),
                    generation + " missing from:\n" + statements());
        }
    }
}

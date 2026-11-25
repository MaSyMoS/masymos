package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;
import org.neo4j.driver.Driver;

class Neo4jSedmlQueryRepositoryTest {
    private static final Driver UNUSED_DRIVER = null;

    @Test
    void theFulltextCypherQueriesTheModelIndexForSedmlDescriptions() {
        var cypher = Neo4jSedmlQueryRepository.sedmlCypher();

        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)"));
        assertTrue(cypher.contains("WHERE node:SEDML_MODEL AND score >= $minScore"));
        assertTrue(cypher.contains("ORDER BY score DESC"));
        assertTrue(cypher.contains("LIMIT $top"));
    }

    @Test
    void theFulltextCypherCollectsTheReferencedModelSources() {
        var cypher = Neo4jSedmlQueryRepository.sedmlCypher();

        assertTrue(cypher.contains("OPTIONAL MATCH (node)-[:HAS_MODELREFERENCE]->(reference:SEDML_MODELREFERENCE)"));
        assertTrue(cypher.contains("collect(DISTINCT coalesce(reference.resolvedSource, reference.modelSource))"
                + " AS referencedModelUris"));
        assertTrue(cypher.contains("HAS_MODELREFERENCE"));
        assertTrue(cypher.contains("SEDML_MODELREFERENCE"));
    }

    @Test
    void theProjectionKeepsTheDocumentRowFields() {
        var cypher = Neo4jSedmlQueryRepository.sedmlCypher();

        assertTrue(cypher.contains("coalesce(node.uid, document.uid) AS uid"));
        assertTrue(cypher.contains("coalesce(document.fileId, node.fileId) AS fileId"));
        assertTrue(cypher.contains("coalesce(node.name, document.fileId) AS modelName"));
        assertTrue(cypher.contains("document.sourceUri AS documentUri"));
        assertTrue(cypher.contains("coalesce(document.versionId, node.versionId) AS versionId"));
    }

    @Test
    void theExactPredicatesAreAndedOntoTheLabelAndTheFloor() {
        var cypher = Neo4jSedmlQueryRepository.sedmlCypher("node.sbmlLevel = $exact0");

        assertTrue(cypher.contains("WHERE node:SEDML_MODEL AND score >= $minScore AND node.sbmlLevel = $exact0"));
        assertTrue(cypher.contains("collect(DISTINCT coalesce(reference.resolvedSource, reference.modelSource))"));
    }

    @Test
    void theExactOnlyCypherTakesTheConstantScoreWithoutTheIndexCall() {
        var cypher = Neo4jSedmlQueryRepository.sedmlExactCypher("node.name = $exact0");

        assertTrue(cypher.contains("MATCH (node:SEDML_MODEL)"));
        assertTrue(cypher.contains("WHERE node.name = $exact0"));
        assertFalse(cypher.contains("queryNodes"));
        assertFalse(cypher.contains("$keyword"));
        assertFalse(cypher.contains("$minScore"));
        assertTrue(cypher.contains("1.0 AS score"));
        assertTrue(cypher.contains("collect(DISTINCT coalesce(reference.resolvedSource, reference.modelSource))"));
        assertTrue(cypher.contains("ORDER BY score DESC"));
        assertTrue(cypher.contains("LIMIT $top"));
    }

    /**
     * An empty plan answers before any session is opened; the absent driver would fail, so an empty
     * list proves no Cypher was executed.
     */
    @Test
    void anEmptyPlanReturnsNoResultsWithoutTheDriver() {
        var repository = new Neo4jSedmlQueryRepository(UNUSED_DRIVER, "neo4j");

        assertEquals(List.of(), repository.querySedml(new ModelQueryRequest(null, List.of(), 10)));
        assertEquals(List.of(), repository.querySedml(new ModelQueryRequest("   ", List.of(), 10)));
    }

    /**
     * {@code URI} is an exact field, so the plan has no text and the floor above the constant score
     * lets the repository answer without opening a session.
     */
    @Test
    void anExactOnlyRequestAboveTheConstantScoreAnswersWithoutTheDriver() {
        var repository = new Neo4jSedmlQueryRepository(UNUSED_DRIVER, "neo4j");
        var request = new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.URI, "https://example.org/BIOMD0000000001")),
                10,
                1.5);
        assertEquals(List.of(), repository.querySedml(request));
    }

    @Test
    void thePlanKeepsSedmlFieldsInTheLuceneText() {
        var plan = Neo4jModelFulltextQuery.plan(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.OUTPUT_TYPE, "plot")),
                10));

        assertTrue(plan.hasText());
        assertTrue(plan.textQuery().contains("outputType:"));
        assertTrue(plan.exactMatches().stream().map(ExactMatch::property).noneMatch("sbmlLevel"::equals));
    }
}

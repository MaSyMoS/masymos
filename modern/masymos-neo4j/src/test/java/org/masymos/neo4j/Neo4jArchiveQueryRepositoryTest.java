package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;
import org.neo4j.driver.Driver;

class Neo4jArchiveQueryRepositoryTest {
    private static final Driver UNUSED_DRIVER = null;

    @Test
    void theFulltextCypherQueriesTheArchiveIndex() {
        var cypher = Neo4jArchiveQueryRepository.archiveCypher();

        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('archiveFulltextV1', $keyword)"));
        assertTrue(cypher.contains("YIELD node, score"));
        assertTrue(cypher.contains("WHERE score >= $minScore"));
        assertTrue(cypher.contains("ORDER BY score DESC"));
        assertTrue(cypher.contains("LIMIT $top"));
    }

    @Test
    void theProjectionReportsTheDocumentCountAndTheArchiveIdentity() {
        var cypher = Neo4jArchiveQueryRepository.archiveCypher();

        assertTrue(cypher.contains("OPTIONAL MATCH (archive)-[:HAS_DOCUMENT]->(document)"));
        assertTrue(cypher.contains("HAS_DOCUMENT"));
        assertTrue(cypher.contains("count(DISTINCT document) AS documentCount"));
        assertTrue(cypher.contains("coalesce(archive.uid, archive.sourceUri) AS uid"));
        assertTrue(cypher.contains("archive.fileId AS fileId"));
        assertTrue(cypher.contains("archive.sourceUri AS sourceUri"));
    }

    @Test
    void theArchiveNameClauseRendersAgainstTheFileId() {
        var plan = Neo4jArchiveQueryRepository.plan(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.ARCHIVE_NAME, "BIOMD1.omex")),
                10));

        assertTrue(plan.hasText());
        assertTrue(plan.textQuery().contains("fileId:(BIOMD1.omex)"));
        assertTrue(plan.textQuery().contains("archive.fileId") || plan.textQuery().contains("fileId:"));
        assertTrue(plan.exactMatches().isEmpty());
    }

    @Test
    void theArchiveUriClauseRendersAgainstTheSourceUri() {
        var plan = Neo4jArchiveQueryRepository.plan(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.ARCHIVE_URI, "https://example.org/a.omex")),
                10));

        assertTrue(plan.hasText());
        assertTrue(plan.textQuery().startsWith("sourceUri:("));
        assertTrue(plan.textQuery().contains("a.omex"));
        // The archive node is written once and has no exact copy, so there is no equality predicate.
        assertTrue(plan.exactMatches().isEmpty());
    }

    @Test
    void aKeywordClauseIsABareEscapedTerm() {
        var plan = Neo4jArchiveQueryRepository.plan(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.KEYWORD, "cancer")),
                10));

        assertEquals("cancer", plan.textQuery());
        assertTrue(plan.exactMatches().isEmpty());
    }

    @Test
    void anEmptyRequestReturnsNoResultsWithoutTheDriver() {
        var repository = new Neo4jArchiveQueryRepository(UNUSED_DRIVER, "neo4j");

        assertEquals(List.of(), repository.queryArchives(new ModelQueryRequest(null, List.of(), 10)));
        assertEquals(List.of(), repository.queryArchives(new ModelQueryRequest("  ", List.of(), 10)));
    }

    /**
     * Archives have no exact copies, so even a request that carries only an {@code ARCHIVE_URI}
     * clause is a plain full-text request: there is no exact-only shortcut to take.
     */
    @Test
    void anArchiveUriOnlyRequestIsAlwaysAFullTextRequest() {
        var plan = Neo4jArchiveQueryRepository.plan(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.ARCHIVE_URI, "https://example.org/a.omex")),
                10,
                1.5));

        assertTrue(plan.hasText());
        assertTrue(plan.exactMatches().isEmpty());
    }
}

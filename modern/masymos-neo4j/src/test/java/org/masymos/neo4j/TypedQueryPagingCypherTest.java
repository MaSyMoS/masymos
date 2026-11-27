package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.PageCursor;

/**
 * Shape guards for the paged SED-ML and archive statements ({@code OPN-6}), mirroring
 * {@link ModelQueryPagingCypherTest} and {@link ResourceQueryPagingCypherTest}. They read the generated
 * Cypher; the sweep itself is proven against a real server by {@code Neo4jContainersIT}.
 *
 * <p>What must hold per lane: every statement orders by a total order whose tiebreak is the identity it
 * projects as {@code nodeIdentity}, the keyset continues exactly that order and sits where both
 * {@code score} and {@code nodeIdentity} are in scope, the page bound is the caller's {@code limit} rather
 * than the old {@code $top}, a statement without a cursor never names one, and an exact-only statement
 * never references {@code $cursorScore} because its score is the constant {@code 1.0}.
 */
class TypedQueryPagingCypherTest {
    private static final PageCursor CURSOR = new PageCursor(1.5, "node-7", "finger");

    private static final String SEDML_IDENTITY = "coalesce(node.uid, document.uid)";
    private static final String ARCHIVE_IDENTITY = "coalesce(node.uid, node.sourceUri, '')";

    @Test
    void theSedmlFullTextStatementContinuesOnScoreThenTheUid() {
        assertScoredShape(Neo4jSedmlQueryRepository.sedmlCypher("", CURSOR), SEDML_IDENTITY);
    }

    @Test
    void theSedmlFullTextStatementKeepsItsTextCallAndFloor() {
        var cypher = Neo4jSedmlQueryRepository.sedmlCypher("node.sbmlLevel = $exact0", CURSOR);

        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)"), cypher);
        assertTrue(cypher.contains("WHERE node:SEDML_MODEL AND score >= $minScore AND node.sbmlLevel = $exact0"),
                cypher);
        assertScoredShape(cypher, SEDML_IDENTITY);
    }

    @Test
    void theSedmlExactStatementContinuesOnTheUidAlone() {
        assertExactShape(Neo4jSedmlQueryRepository.sedmlExactCypher("node.name = $exact0", CURSOR));
    }

    @Test
    void theArchiveStatementContinuesOnScoreThenUidAndSourceUri() {
        assertScoredShape(Neo4jArchiveQueryRepository.archiveCypher(CURSOR), ARCHIVE_IDENTITY);
    }

    @Test
    void theSedmlKeysetSitsInTheWithThatBindsScoreAndIdentity() {
        var cypher = Neo4jSedmlQueryRepository.sedmlCypher("", CURSOR);
        var with = cypher.indexOf("AS nodeIdentity");

        assertTrue(with < cypher.indexOf("$cursorIdentity"),
                "the keyset must follow the WITH binding the identity, not precede it: " + cypher);
        assertTrue(cypher.indexOf("$cursorIdentity") < cypher.indexOf("HAS_MODELREFERENCE"),
                "a keyset after the reference traversal would page references, not hits: " + cypher);
        // The identity is bound in the WITH, so the projection returns it as a carried grouping key.
        assertTrue(cypher.lines().anyMatch(line -> line.trim().equals("nodeIdentity")), cypher);
    }

    @Test
    void theArchiveKeysetSitsInTheWithThatBindsScoreAndIdentity() {
        var cypher = Neo4jArchiveQueryRepository.archiveCypher(CURSOR);

        assertTrue(cypher.indexOf("AS nodeIdentity") < cypher.indexOf("$cursorIdentity"),
                "the keyset must follow the WITH binding the identity, not precede it: " + cypher);
        assertTrue(cypher.indexOf("$cursorIdentity") < cypher.indexOf("HAS_DOCUMENT"),
                "a keyset after the document traversal would page documents, not archives: " + cypher);
        assertTrue(cypher.lines().anyMatch(line -> line.trim().equals("nodeIdentity")), cypher);
    }

    @Test
    void theUnpagedOverloadsKeepTheirStatementShape() {
        var unpaged = List.of(
                Neo4jSedmlQueryRepository.sedmlCypher(),
                Neo4jSedmlQueryRepository.sedmlCypher("node.sbmlLevel = $exact0"),
                Neo4jSedmlQueryRepository.sedmlExactCypher("node.name = $exact0"),
                Neo4jArchiveQueryRepository.archiveCypher());

        for (var cypher : unpaged) {
            assertFalse(cypher.contains("$cursorScore"), "no keyset without a cursor: " + cypher);
            assertFalse(cypher.contains("cursor"), "no cursor named without one: " + cypher);
            assertTrue(cypher.contains("LIMIT $limit"), cypher);
            assertFalse(cypher.contains("$top"), "the page bound is the caller's limit: " + cypher);
        }
    }

    private static void assertScoredShape(String cypher, String identity) {
        assertTrue(cypher.contains("ORDER BY score DESC, nodeIdentity"), cypher);
        assertTrue(cypher.contains("LIMIT $limit"), cypher);
        assertFalse(cypher.contains("$top"), "the page bound is the caller's limit: " + cypher);
        assertTrue(
                cypher.contains("score < $cursorScore OR (score = $cursorScore AND " + identity + " > $cursorIdentity)"),
                cypher);
        assertTrue(cypher.contains(identity + " AS nodeIdentity"), cypher);
    }

    private static void assertExactShape(String cypher) {
        assertTrue(cypher.contains("ORDER BY score DESC, nodeIdentity"), cypher);
        assertTrue(cypher.contains("AND (" + SEDML_IDENTITY + " > $cursorIdentity)"), cypher);
        assertFalse(cypher.contains("$cursorScore"), "an exact-only hit has a constant score: " + cypher);
        assertTrue(cypher.contains("LIMIT $limit"), cypher);
        assertTrue(cypher.contains("1.0 AS score"), cypher);
        assertTrue(cypher.contains(SEDML_IDENTITY + " AS nodeIdentity"), cypher);
    }
}

package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.PageCursor;

/**
 * Shape guards for the paged model statements ({@code OPN-6}). They read the generated Cypher; the sweep
 * itself is proven against a real server by {@code Neo4jContainersIT}.
 *
 * <p>What must hold: every statement orders by a total order (the tiebreak is {@code elementId(node)} on the
 * flat lanes and {@code fileId} on the grouped ones), the keyset continues exactly that order, the page
 * bound is the caller's {@code limit} rather than the old {@code $top}, and the identity the cursor needs is
 * projected under its own name instead of being smuggled through a public field.
 */
class ModelQueryPagingCypherTest {
    private static final PageCursor CURSOR = new PageCursor(1.5, "node-7", "finger");

    @Test
    void theFlatFullTextStatementOrdersByScoreThenIdentity() {
        var cypher = Neo4jModelRepository.queryModelsCypher(List.of(), List.of(), List.of(), CURSOR);

        assertTrue(cypher.contains("ORDER BY score DESC, elementId(node)"), cypher);
        assertTrue(cypher.contains("LIMIT $limit"), cypher);
        assertFalse(cypher.contains("$top"), "the page bound is the caller's limit: " + cypher);
        assertTrue(
                cypher.contains("score < $cursorScore OR (score = $cursorScore AND elementId(node) > $cursorIdentity)"),
                cypher);
        assertTrue(cypher.contains("elementId(node) AS nodeIdentity"), cypher);
    }

    @Test
    void theFlatExactStatementContinuesOnTheIdentityAlone() {
        var cypher = Neo4jModelRepository.queryModelsExactCypher(List.of(), List.of(), List.of(), CURSOR);

        assertTrue(cypher.contains("ORDER BY elementId(node)"), cypher);
        assertTrue(cypher.contains("elementId(node) > $cursorIdentity"), cypher);
        assertFalse(cypher.contains("$cursorScore"), "an exact-only hit has a constant score: " + cypher);
        assertTrue(cypher.contains("elementId(node) AS nodeIdentity"), cypher);
    }

    @Test
    void theGroupedFullTextStatementFiltersGroupsAfterTheRepresentativeIsChosen() {
        var cypher = Neo4jModelRepository.groupedModelsCypher(List.of(), List.of(), List.of(), CURSOR);

        assertTrue(cypher.contains("ORDER BY score DESC, fileId"), cypher);
        assertTrue(
                cypher.contains("WHERE best.score < $cursorScore OR (best.score = $cursorScore AND fileId > $cursorIdentity)"),
                cypher);
        assertTrue(cypher.contains("fileId AS nodeIdentity"), cypher);
        assertTrue(
                cypher.indexOf("AS best") < cypher.indexOf("WHERE best.score"),
                "the keyset must follow the reduce that names best: " + cypher);
    }

    @Test
    void theGroupedExactStatementContinuesOnTheGroupKey() {
        var cypher = Neo4jModelRepository.groupedModelsExactCypher(List.of(), List.of(), List.of(), CURSOR);

        assertTrue(cypher.contains("ORDER BY fileId"), cypher);
        assertTrue(cypher.contains("WHERE fileId > $cursorIdentity"), cypher);
        assertFalse(cypher.contains("$cursorScore"), cypher);
    }

    @Test
    void theUnpagedOverloadsKeepTheirStatementShape() {
        var flat = Neo4jModelRepository.queryModelsCypher(List.of(), List.of(), List.of());
        var grouped = Neo4jModelRepository.groupedModelsCypher(List.of(), List.of(), List.of());

        assertFalse(flat.contains("$cursorScore"), "no keyset without a cursor: " + flat);
        assertFalse(grouped.contains("$cursorScore"), "no keyset without a cursor: " + grouped);
        assertTrue(flat.contains("LIMIT $limit"), flat);
        assertTrue(grouped.contains("LIMIT $limit"), grouped);
    }
}

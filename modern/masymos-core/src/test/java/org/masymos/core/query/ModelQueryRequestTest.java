package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelType;

class ModelQueryRequestTest {
    @Test
    void normalizesMissingClausesAndInvalidLimit() {
        var request = new ModelQueryRequest("novak", null, 0);

        assertEquals("novak", request.keyword());
        assertTrue(request.clauses().isEmpty());
        assertEquals(10, request.top());
    }

    @Test
    void absentFloorKeepsTheDefaultLimit() {
        var request = new ModelQueryRequest(null, null, 0, null);

        assertNull(request.minScore());
        assertEquals(10, request.top());
        assertTrue(request.clauses().isEmpty());
    }

    @Test
    void theThreeArgumentConstructorLeavesTheFloorUnset() {
        var request = new ModelQueryRequest("x", null, 5);

        assertNull(request.minScore());
        assertEquals(5, request.top());
    }

    @Test
    void keepsTheFloorWhenGiven() {
        assertEquals(0.5, new ModelQueryRequest(null, null, 5, 0.5).minScore());
    }

    @Test
    void groupingIsOffByDefaultInEveryConvenienceConstructor() {
        assertFalse(new ModelQueryRequest("x", null, 10).groupByVersion());
        assertFalse(new ModelQueryRequest("x", null, 10, null).groupByVersion());
        assertFalse(new ModelQueryRequest("x", null, 10, 0.5).groupByVersion());
    }

    @Test
    void keepsTheGroupingFlagWhenGiven() {
        var request = new ModelQueryRequest(null, null, 10, null, true);

        assertTrue(request.groupByVersion());
        assertEquals(10, request.top());
        assertTrue(request.clauses().isEmpty());
    }

    @Test
    void theOldConstructorsAllReportAnEmptyKindFilter() {
        assertEquals(Set.of(), new ModelQueryRequest("x", null, 10).modelTypes());
        assertEquals(Set.of(), new ModelQueryRequest("x", null, 10, null).modelTypes());
        assertEquals(Set.of(), new ModelQueryRequest("x", null, 10, 0.5).modelTypes());
        assertEquals(Set.of(), new ModelQueryRequest("x", null, 10, null, true).modelTypes());
    }

    @Test
    void keepsTheKindFilterWhenGiven() {
        var request = new ModelQueryRequest("x", null, 10, null, false, Set.of(ModelType.SBML));

        assertEquals(Set.of(ModelType.SBML), request.modelTypes());
        assertFalse(request.groupByVersion());
        assertEquals(10, request.top());
    }

    @Test
    void aNullKindFilterBecomesEmpty() {
        var request = new ModelQueryRequest("x", null, 10, null, false, null);

        assertEquals(Set.of(), request.modelTypes());
    }

    /**
     * {@code FT-001}: one table bounds keyword length, clause count and the rendered length, and this
     * record is where they are enforced - it is the only place that sees the keyword and the clauses
     * together. Each bound is asserted in both directions, because a cap that refuses everything would
     * pass a one-sided test.
     */
    @Test
    void oneThresholdTableBoundsKeywordClausesAndRenderedLength() {
        assertDoesNotThrow(() -> new ModelQueryRequest("x".repeat(ModelQueryRequest.MAX_KEYWORD_CHARS), null, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelQueryRequest("x".repeat(ModelQueryRequest.MAX_KEYWORD_CHARS + 1), null, 10));

        assertDoesNotThrow(() -> new ModelQueryRequest(null, clausesOf(ModelQueryRequest.MAX_CLAUSES, 4), 10));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelQueryRequest(null, clausesOf(ModelQueryRequest.MAX_CLAUSES + 1, 4), 10));

        // eight full-length clauses render far past the request's own rendered bound
        assertThrows(IllegalArgumentException.class,
                () -> new ModelQueryRequest(null, clausesOf(8, ModelQueryRequest.MAX_KEYWORD_CHARS), 10));

        var message = assertThrows(IllegalArgumentException.class, () -> new ModelQueryRequest(
                "x".repeat(ModelQueryRequest.MAX_KEYWORD_CHARS),
                clausesOf(4, ModelQueryRequest.MAX_KEYWORD_CHARS), 10)).getMessage();
        assertTrue(message.contains(String.valueOf(ModelQueryRequest.MAX_RENDERED_QUERY_CHARS)), message);
    }

    private static List<QueryClause> clausesOf(int count, int keywordLength) {
        return IntStream.range(0, count)
                .mapToObj(index -> new QueryClause(QueryField.NAME, "y".repeat(keywordLength)))
                .toList();
    }
}

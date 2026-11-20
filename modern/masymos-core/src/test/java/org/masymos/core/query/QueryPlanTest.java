package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryPlanTest {
    @Test
    void aBlankTextAndNoExactMatchIsEmpty() {
        assertTrue(new QueryPlan(" ", List.of()).isEmpty());
        assertTrue(new QueryPlan(null, null).isEmpty());
        assertFalse(new QueryPlan(null, null).hasText());
    }

    @Test
    void aTextQueryIsNotExactOnly() {
        var plan = new QueryPlan("a", List.of(new ExactMatch("p", List.of("v"))));

        assertTrue(plan.hasText());
        assertFalse(plan.isEmpty());
        assertEquals("a", plan.textQuery());
        assertEquals(List.of(new ExactMatch("p", List.of("v"))), plan.exactMatches());
    }

    @Test
    void exactOnlyIsNotTextAndNotEmpty() {
        var plan = new QueryPlan(null, List.of(new ExactMatch("p", List.of("v"))));

        assertFalse(plan.hasText());
        assertFalse(plan.isEmpty());
        assertEquals("", plan.textQuery());
    }
}

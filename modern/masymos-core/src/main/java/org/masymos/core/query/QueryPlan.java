package org.masymos.core.query;

import java.util.List;

/**
 * A parsed query request in the two parts the Cypher builders assemble: the Lucene expression for
 * the full-text index (empty when the request is exact-only) and the equality predicates that are
 * ANDed onto it.
 */
public record QueryPlan(String textQuery, List<ExactMatch> exactMatches) {
    public QueryPlan {
        textQuery = textQuery == null ? "" : textQuery;
        exactMatches = exactMatches == null ? List.of() : List.copyOf(exactMatches);
    }

    public boolean hasText() {
        return !textQuery.isBlank();
    }

    /** True when the request carries nothing to query and the caller can answer with no result. */
    public boolean isEmpty() {
        return !hasText() && exactMatches.isEmpty();
    }
}

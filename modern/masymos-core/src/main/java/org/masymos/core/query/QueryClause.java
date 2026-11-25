package org.masymos.core.query;

import java.util.Objects;

public record QueryClause(QueryField field, String keyword) {
    public QueryClause {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(keyword, "keyword");
        if (keyword.isBlank()) {
            throw new IllegalArgumentException("keyword must not be blank");
        }
    }
}

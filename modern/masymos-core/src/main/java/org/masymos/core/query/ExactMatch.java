package org.masymos.core.query;

import java.util.List;
import java.util.Objects;

/**
 * One equality predicate of a query plan: the node property and the values it may equal, combined
 * with OR. The values are already normalized by {@link ExactText}.
 */
public record ExactMatch(String property, List<String> values) {
    public ExactMatch {
        Objects.requireNonNull(property, "property");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("values must not contain null or blank values");
        }
        values = List.copyOf(values);
    }
}

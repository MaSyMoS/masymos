package org.masymos.core.extract;

import java.util.Map;

public record ParsedOntologyTerm(
        String id,
        Map<String, String> properties
) {
}

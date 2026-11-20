package org.masymos.core.extract;

import java.util.Map;

public record ParsedOntologyRelationship(
        String startId,
        String type,
        String endId,
        Map<String, String> properties
) {
}

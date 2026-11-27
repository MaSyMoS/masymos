package org.masymos.core.extract;

import java.util.Map;

public record ParsedEntityRelationship(
        String startEntityKey,
        String type,
        String endEntityKey,
        Map<String, String> properties
) {
    public ParsedEntityRelationship {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}

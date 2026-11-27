package org.masymos.core.extract;

import java.util.Map;

public record ExtractedGraphRelationship(
        String startKey,
        String type,
        String endKey,
        Map<String, String> properties
) {
}

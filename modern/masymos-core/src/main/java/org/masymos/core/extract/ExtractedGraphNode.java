package org.masymos.core.extract;

import java.util.Map;
import java.util.Set;

public record ExtractedGraphNode(
        String key,
        Set<String> labels,
        Map<String, String> properties
) {
}

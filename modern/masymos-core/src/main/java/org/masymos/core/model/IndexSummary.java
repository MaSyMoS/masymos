package org.masymos.core.model;

import java.util.List;

/**
 * One index as the store reports it. The fields mirror the store's own catalogue ({@code SHOW INDEXES}
 * in Neo4j) rather than a MaSyMoS-specific view, so a listing never claims an index the store does not
 * have — an index that failed to create is simply absent.
 *
 * @param name index name, for example {@code modelFulltextV4}
 * @param type index kind, for example {@code FULLTEXT} or {@code RANGE}
 * @param entityType what the index covers, {@code NODE} or {@code RELATIONSHIP}
 * @param labelsOrTypes the node labels or relationship types the index applies to
 * @param properties the indexed properties
 * @param state store state, for example {@code ONLINE}
 */
public record IndexSummary(
        String name,
        String type,
        String entityType,
        List<String> labelsOrTypes,
        List<String> properties,
        String state) {
    public IndexSummary {
        labelsOrTypes = labelsOrTypes == null ? List.of() : List.copyOf(labelsOrTypes);
        properties = properties == null ? List.of() : List.copyOf(properties);
    }
}

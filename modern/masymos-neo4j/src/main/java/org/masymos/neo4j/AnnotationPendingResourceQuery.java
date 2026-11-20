package org.masymos.neo4j;

import org.masymos.core.model.AnnotationIndexingOptions;

/**
 * Cypher for selecting the {@code RESOURCE} nodes that still need annotation enrichment.
 *
 * <p>Extracted from the service so the bounded-run semantics stay unit-testable without a database.
 */
public final class AnnotationPendingResourceQuery {

    private static final String SELECT = """
            MATCH (resource:RESOURCE)
            WHERE coalesce(resource.isIndexed, false) = false
            """;
    private static final String EXCLUDE_FAILED = "  AND coalesce(resource.indexStatus, '') <> 'failed'\n";
    private static final String RETURN = """
            RETURN elementId(resource) AS id, resource.uri AS uri
            ORDER BY id
            """;
    private static final String LIMIT = "LIMIT $limit";

    private AnnotationPendingResourceQuery() {
    }

    public static String pendingResources(AnnotationIndexingOptions options) {
        var query = new StringBuilder(SELECT);
        if (!options.retryFailed()) {
            query.append(EXCLUDE_FAILED);
        }
        query.append(RETURN);
        if (options.hasLimit()) {
            query.append(LIMIT);
        }
        return query.toString();
    }
}

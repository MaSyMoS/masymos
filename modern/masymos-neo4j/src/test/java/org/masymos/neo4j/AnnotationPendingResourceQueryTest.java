package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.masymos.core.model.AnnotationIndexingOptions;

class AnnotationPendingResourceQueryTest {

    @Test
    void excludesFailedResourcesByDefault() {
        var query = AnnotationPendingResourceQuery.pendingResources(AnnotationIndexingOptions.defaults());

        assertTrue(query.contains("MATCH (resource:RESOURCE)"), query);
        assertTrue(query.contains("coalesce(resource.isIndexed, false) = false"), query);
        assertTrue(query.contains("resource.indexStatus"), query);
        assertTrue(query.endsWith("LIMIT $limit"), query);
    }

    @Test
    void includesFailedResourcesWhenRetrying() {
        var options = new AnnotationIndexingOptions(false, 10, true, 4);

        var query = AnnotationPendingResourceQuery.pendingResources(options);

        assertFalse(query.contains("indexStatus"), query);
        assertTrue(query.endsWith("LIMIT $limit"), query);
    }

    @Test
    void omitsLimitWhenRunIsUnbounded() {
        var options = new AnnotationIndexingOptions(false, 0, false, 4);

        var query = AnnotationPendingResourceQuery.pendingResources(options);

        assertFalse(query.contains("LIMIT"), query);
        assertTrue(query.contains("ORDER BY id"), query);
    }
}

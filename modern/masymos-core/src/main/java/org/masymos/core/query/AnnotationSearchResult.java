package org.masymos.core.query;

import java.net.URI;
import java.util.List;

public record AnnotationSearchResult(
        String uri,
        List<URI> relatedModelUris,
        double score
) {
    public AnnotationSearchResult {
        relatedModelUris = relatedModelUris == null ? List.of() : List.copyOf(relatedModelUris);
    }
}

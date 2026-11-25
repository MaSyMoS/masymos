package org.masymos.core.query;

import java.net.URI;
import java.util.List;

public record PersonSearchResult(
        PersonSummary person,
        List<URI> relatedModelUris,
        double score
) {
    public PersonSearchResult {
        relatedModelUris = relatedModelUris == null ? List.of() : List.copyOf(relatedModelUris);
    }
}

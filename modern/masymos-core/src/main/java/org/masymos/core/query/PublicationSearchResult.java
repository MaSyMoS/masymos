package org.masymos.core.query;

import java.net.URI;
import java.util.List;

public record PublicationSearchResult(
        String title,
        String journal,
        String affiliation,
        String year,
        List<PersonSummary> authors,
        List<URI> relatedModelUris,
        double score
) {
    public PublicationSearchResult {
        authors = authors == null ? List.of() : List.copyOf(authors);
        relatedModelUris = relatedModelUris == null ? List.of() : List.copyOf(relatedModelUris);
    }
}

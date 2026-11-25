package org.masymos.core.annotation;

import java.util.List;

/**
 * Outcome for one annotation resource URI.
 *
 * <p>The searchable text is index-only enrichment; the resource URI stays the durable identity.
 */
public record AnnotationEnrichmentOutcome(
        String resourceUri,
        Status status,
        List<String> candidateUrls,
        String searchableText,
        String failureReason
) {
    public AnnotationEnrichmentOutcome {
        candidateUrls = candidateUrls == null ? List.of() : List.copyOf(candidateUrls);
        searchableText = searchableText == null ? "" : searchableText;
    }

    public boolean indexed() {
        return status == Status.INDEXED;
    }

    public enum Status {
        INDEXED,
        SKIPPED,
        FAILED
    }
}

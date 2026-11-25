package org.masymos.core.annotation;

import java.util.List;

/** Bounded settings for one annotation enrichment run. */
public record AnnotationEnrichmentRequest(
        List<String> resourceUris,
        int maxConcurrency,
        int maxCandidatesPerResource,
        int maxTextLength
) {
    public static final int DEFAULT_MAX_CONCURRENCY = 4;
    public static final int DEFAULT_MAX_CANDIDATES_PER_RESOURCE = 3;
    public static final int DEFAULT_MAX_TEXT_LENGTH = AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH;

    public AnnotationEnrichmentRequest {
        resourceUris = resourceUris == null ? List.of() : List.copyOf(resourceUris);
        maxConcurrency = maxConcurrency < 1 ? DEFAULT_MAX_CONCURRENCY : maxConcurrency;
        maxCandidatesPerResource = maxCandidatesPerResource < 1 ? DEFAULT_MAX_CANDIDATES_PER_RESOURCE : maxCandidatesPerResource;
        maxTextLength = maxTextLength < 1 ? DEFAULT_MAX_TEXT_LENGTH : maxTextLength;
    }

    public static AnnotationEnrichmentRequest of(List<String> resourceUris) {
        return new AnnotationEnrichmentRequest(
                resourceUris,
                DEFAULT_MAX_CONCURRENCY,
                DEFAULT_MAX_CANDIDATES_PER_RESOURCE,
                DEFAULT_MAX_TEXT_LENGTH
        );
    }
}

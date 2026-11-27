package org.masymos.core.annotation;

import java.util.List;

/**
 * Result of resolving one durable annotation URI into candidate fetch URLs.
 *
 * <p>The original URI always stays the durable identity. Candidate URLs are only used to fetch
 * page text for search-index enrichment.
 */
public record AnnotationResolution(
        String originalUri,
        String resourcePrefix,
        String identifier,
        Strategy strategy,
        List<String> candidateUrls
) {
    public AnnotationResolution {
        candidateUrls = candidateUrls == null ? List.of() : List.copyOf(candidateUrls);
    }

    public static AnnotationResolution unresolved(String originalUri) {
        return new AnnotationResolution(originalUri, null, null, Strategy.UNRESOLVED, List.of());
    }

    public boolean resolvable() {
        return !candidateUrls.isEmpty();
    }

    public enum Strategy {
        DIRECT_URL,
        IDENTIFIERS_ORG_URL,
        MIRIAM_URN,
        UNRESOLVED
    }
}

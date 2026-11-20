package org.masymos.core.model;

import java.util.Objects;
import org.masymos.core.annotation.ResolverMode;

/**
 * Options for one explicit annotation maintenance run.
 *
 * <p>Annotation enrichment is network-bound and failure-prone, so every run is bounded and every
 * run is explicit. {@code limit = 0} means "no limit".
 *
 * <p>{@code resolverMode} truncates the term chain ({@code providers}, {@code direct}, {@code off})
 * and {@code refreshTerms} treats the cached terms of this run as stale for this run only. Both
 * default to today's behaviour: {@link ResolverMode#DIRECT} and {@code false}.
 */
public record AnnotationIndexingOptions(
        boolean dropExistingIndex,
        int limit,
        boolean retryFailed,
        int maxConcurrency,
        ResolverMode resolverMode,
        boolean refreshTerms
) {
    public static final int DEFAULT_LIMIT = 500;
    public static final int DEFAULT_MAX_CONCURRENCY = 4;

    public AnnotationIndexingOptions {
        limit = limit < 0 ? DEFAULT_LIMIT : limit;
        maxConcurrency = maxConcurrency < 1 ? DEFAULT_MAX_CONCURRENCY : maxConcurrency;
        resolverMode = Objects.requireNonNull(resolverMode, "resolverMode");
    }

    /**
     * The four-argument form kept for callers that predate the term stage: it supplies the default
     * mode and no refresh.
     */
    public AnnotationIndexingOptions(
            boolean dropExistingIndex,
            int limit,
            boolean retryFailed,
            int maxConcurrency
    ) {
        this(dropExistingIndex, limit, retryFailed, maxConcurrency, ResolverMode.DIRECT, false);
    }

    public static AnnotationIndexingOptions defaults() {
        return new AnnotationIndexingOptions(
                false, DEFAULT_LIMIT, false, DEFAULT_MAX_CONCURRENCY, ResolverMode.DIRECT, false);
    }

    public AnnotationIndexingOptions withDropExistingIndex(boolean value) {
        return new AnnotationIndexingOptions(value, limit, retryFailed, maxConcurrency, resolverMode, refreshTerms);
    }

    public boolean hasLimit() {
        return limit > 0;
    }
}

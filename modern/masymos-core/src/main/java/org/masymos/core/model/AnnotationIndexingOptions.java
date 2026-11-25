package org.masymos.core.model;

import java.util.Objects;
import org.masymos.core.annotation.ResolverMode;

/**
 * Options for one explicit annotation maintenance run.
 *
 * <p>Annotation enrichment is network-bound and failure-prone, so every run is bounded and every
 * run is explicit. {@code limit = 0} means "no limit" - and that is exactly what it does to the
 * Cypher: the statement's {@code LIMIT} is left out, so one run over a large graph can hold a
 * request thread for as long as the whole annotation set takes. {@code REL-004}'s second half is that
 * unbounded case; it is recorded here and left open pending decision {@code D-8}, because the remedy
 * (a resume cursor) needs a stable document ordering this record cannot choose. {@link #hasLimit()}
 * is the one predicate a caller should ask.
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

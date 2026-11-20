package org.masymos.core.model;

import java.util.List;
import java.util.Objects;
import org.masymos.core.annotation.ResolverMode;

/**
 * What one explicit annotation maintenance run did.
 *
 * <p>{@code terms} is nullable on purpose: a run that does not measure the term stage — disabled
 * storage, a test double, a caller that only counts resources — passes {@code null}, and nothing
 * claims a resolver mode that was not run.
 */
public record AnnotationIndexingResult(
        long scannedResources,
        long indexedResources,
        long skippedResources,
        long failedResources,
        TermResolutionSummary terms
) {

    /** A run that did not measure the term stage. */
    public AnnotationIndexingResult(
            long scannedResources,
            long indexedResources,
            long skippedResources,
            long failedResources
    ) {
        this(scannedResources, indexedResources, skippedResources, failedResources, null);
    }

    /**
     * The term stage's outcome for one run: the mode that actually ran, the providers that could
     * have answered, and the counters that prove the request economy.
     *
     * @param providersDemoted providers that failed during this run and were not asked again, named
     *     once each
     * @param pageRequests keys the page fallback actually fetched
     * @param cacheFailures cache reads or writes that threw and were swallowed, so a silently
     *     unreadable cache is distinguishable from a cold one
     */
    public record TermResolutionSummary(
            ResolverMode mode,
            List<String> providersAvailable,
            long termsRequested,
            long providerRequests,
            long cacheHits,
            long cacheMisses,
            long unresolvedTerms,
            List<String> providersDemoted,
            long pageRequests,
            long cacheFailures
    ) {

        public TermResolutionSummary {
            Objects.requireNonNull(mode, "mode");
            providersAvailable = providersAvailable == null ? List.of() : List.copyOf(providersAvailable);
            providersDemoted = providersDemoted == null ? List.of() : List.copyOf(providersDemoted);
        }
    }
}

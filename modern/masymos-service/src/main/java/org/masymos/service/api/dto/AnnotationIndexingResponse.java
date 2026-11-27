package org.masymos.service.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.masymos.core.model.AnnotationIndexingResult;

/**
 * Counters describing one annotation indexing run.
 *
 * <p>The term counters are nullable on purpose: a run that did not measure the term stage reports
 * nothing for them, and {@link JsonInclude.Include#NON_NULL} keeps them out of the JSON entirely. A
 * caller that did not measure terms must not read zeroes as measurements.
 */
@Schema(description = "Counters describing one annotation indexing run.")
public record AnnotationIndexingResponse(
        @Schema(description = "Number of resources scanned.", example = "12")
        long scannedResources,
        @Schema(description = "Number of resources whose annotations were indexed.", example = "9")
        long indexedResources,
        @Schema(description = "Number of resources skipped, e.g. already indexed or unsupported.", example = "2")
        long skippedResources,
        @Schema(description = "Number of resources that failed during annotation extraction.", example = "1")
        long failedResources,
        @Schema(
                description = "The resolver mode the run used; absent when the run did not measure the term stage.",
                example = "direct",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String resolverMode,
        @Schema(
                description = "Provider sources that could have answered, in chain order.",
                example = "[\"bioportal\"]",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<String> providersAvailable,
        @Schema(description = "Distinct ontological terms this run asked about.", example = "42", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long termsRequested,
        @Schema(description = "Provider requests made, never one per resource.", example = "3", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long providerRequests,
        @Schema(description = "Term cache hits.", example = "30", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long cacheHits,
        @Schema(description = "Term cache misses.", example = "12", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long cacheMisses,
        @Schema(description = "Terms the run recorded unresolved.", example = "1", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long unresolvedTerms,
        @Schema(description = "Providers that failed and were demoted for the rest of the run.", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<String> providersDemoted,
        @Schema(description = "Page fallback fetches, one per distinct term at most.", example = "12", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long pageRequests,
        @Schema(description = "Cache reads or writes that threw, so a broken cache is not a cold one.", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long cacheFailures
) {
    public static AnnotationIndexingResponse from(AnnotationIndexingResult result) {
        var terms = result.terms();
        if (terms == null) {
            return new AnnotationIndexingResponse(
                    result.scannedResources(),
                    result.indexedResources(),
                    result.skippedResources(),
                    result.failedResources(),
                    null, null, null, null, null, null, null, null, null, null
            );
        }
        return new AnnotationIndexingResponse(
                result.scannedResources(),
                result.indexedResources(),
                result.skippedResources(),
                result.failedResources(),
                terms.mode().wire(),
                terms.providersAvailable(),
                terms.termsRequested(),
                terms.providerRequests(),
                terms.cacheHits(),
                terms.cacheMisses(),
                terms.unresolvedTerms(),
                terms.providersDemoted(),
                terms.pageRequests(),
                terms.cacheFailures()
        );
    }
}

package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import org.masymos.core.annotation.ResolverMode;
import org.masymos.core.model.AnnotationIndexingOptions;
import org.masymos.core.spi.AnnotationIndexService;
import org.masymos.service.api.dto.AnnotationIndexingResponse;
import org.masymos.service.config.AnnotationProviderConfiguration.AnnotationProviderAvailability;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The explicit annotation maintenance run.
 *
 * <p>The endpoint accepts a {@code resolverMode} truncating the term chain and a {@code refreshTerms}
 * flag scoped to the run, and it refuses — before any work — the two combinations a run cannot honour
 * (design answers 11 and 12): {@code providers} with no usable provider ({@code 409}, naming the
 * unset environment variables) and a drop under {@code off} ({@code 400}, because {@code off} restores
 * term-backed text only).
 *
 * <p>Each refusal is an RFC 7807 problem detail carrying its reason. It is returned, not thrown, so
 * the reason reaches the caller as the project's existing {@code detail} member instead of a bare
 * status the shared handler would render with no body.
 */
@RestController
@RequestMapping("/api/v1/maintenance/annotations")
public class AnnotationMaintenanceController {
    private final AnnotationIndexService annotationIndexService;
    private final AnnotationProviderAvailability providerAvailability;

    public AnnotationMaintenanceController(
            AnnotationIndexService annotationIndexService,
            AnnotationProviderAvailability providerAvailability
    ) {
        this.annotationIndexService = annotationIndexService;
        this.providerAvailability = providerAvailability;
    }

    @Operation(
            summary = "Rebuild the annotation keyword index",
            description = "Explicit, bounded enrichment run. resolverMode truncates the term chain "
                    + "(providers, direct, off); refreshTerms treats this run's cached terms as stale.")
    @ApiResponse(
            responseCode = "200",
            description = "Run counters; the term counters are absent when the run did not measure them.",
            content = @Content(schema = @Schema(implementation = AnnotationIndexingResponse.class)))
    @ApiResponse(responseCode = "400", description = "Unknown resolverMode, or dropExistingIndex with resolverMode=off.")
    @ApiResponse(responseCode = "409", description = "resolverMode=providers with no usable provider configured.")
    @PostMapping("/index")
    public Object rebuild(
            @RequestParam(name = "dropExistingIndex", defaultValue = "false") boolean dropExistingIndex,
            @RequestParam(name = "limit", defaultValue = "500") int limit,
            @RequestParam(name = "retryFailed", defaultValue = "false") boolean retryFailed,
            @RequestParam(name = "maxConcurrency", defaultValue = "4") int maxConcurrency,
            @RequestParam(name = "resolverMode", defaultValue = "direct") String resolverMode,
            @RequestParam(name = "refreshTerms", defaultValue = "false") boolean refreshTerms
    ) {
        var mode = ResolverMode.fromWire(resolverMode);
        var refusal = refuseUnhonourableMode(mode, dropExistingIndex);
        if (refusal != null) {
            return refusal;
        }
        var options = new AnnotationIndexingOptions(
                dropExistingIndex, limit, retryFailed, maxConcurrency, mode, refreshTerms);
        return AnnotationIndexingResponse.from(annotationIndexService.rebuild(options));
    }

    /**
     * Refuses the combinations that would silently degrade the run, or returns {@code null} when the
     * run can be honoured: a {@code providers} run with nothing configured ({@code 409}, naming the
     * environment variables to set) and a drop under {@code off} ({@code 400}, because {@code off}
     * restores term-backed text only). An unknown mode never reaches here: {@link
     * ResolverMode#fromWire} rejects it with the accepted values, which the shared handler renders as
     * the {@code 400}.
     */
    private ProblemDetail refuseUnhonourableMode(ResolverMode mode, boolean dropExistingIndex) {
        if (mode == ResolverMode.PROVIDERS && !providerAvailability.anyAvailable()) {
            var problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT,
                    "resolverMode=providers needs at least one configured ontology term provider; set one of: "
                            + String.join(", ", providerAvailability.missingVariables()));
            problem.setType(URI.create("https://masymos.org/problems/provider-unavailable"));
            problem.setTitle("No ontology term provider available");
            problem.setProperty("missingVariables", providerAvailability.missingVariables());
            return problem;
        }
        if (mode == ResolverMode.OFF && dropExistingIndex) {
            var problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "dropExistingIndex=true cannot be combined with resolverMode=off: off restores only "
                            + "term-backed text, so the non-ontological resources would lose their text with "
                            + "nothing to restore it; use direct or providers");
            problem.setType(URI.create("https://masymos.org/problems/invalid-argument"));
            problem.setTitle("Invalid argument");
            return problem;
        }
        return null;
    }
}

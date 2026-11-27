package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.masymos.service.config.IndexBoundsProperties;

@Schema(description = "Request to index one or more models or ontologies.")
public record IndexModelsRequest(
        @NotEmpty @Schema(description = "URIs of the documents to index.",
                example = "[\"file:///path/to/model.sbml\"]")
        List<@NotNull URI> sources
) {
    /**
     * Refuses a source list above {@code masymos.index.max-sources-per-request} (task T-018).
     *
     * <p>It is called by every index controller before the first document is read, so an over-cap
     * request is refused whole: the bound is a request bound, not a partial credit. It does not make a
     * <em>run</em> atomic - a failure at document 51 still leaves the first 50 written and the response
     * says nothing about them, which is deferred as {@code C04-1} (a per-source outcome field is a
     * larger surface than this bound).
     *
     * @throws IllegalArgumentException over the cap; the shared handler answers {@code 400
     *     invalid-argument}
     */
    public IndexModelsRequest requireWithin(IndexBoundsProperties bounds) {
        bounds.requireWithinSourceCap(sources);
        return this;
    }
}

package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import org.masymos.core.extract.IndexedModel;
import org.masymos.core.model.SedmlLinkingResult;

@Schema(description = "Result of indexing SED-ML documents, including the automatic cross-document linking outcome.")
public record SedmlIndexResponse(
        @Schema(description = "Generated model UID in the graph.", example = "model_0001")
        String uid,
        @Schema(description = "File identifier of the indexed model.", example = "BIOMD0000000001")
        String fileId,
        @Schema(description = "Source URI the model was read from.",
                example = "file:///path/to/model.sbml")
        URI sourceUri,
        @Schema(description = "Outcome of the automatic SED-ML link step: COMPLETED when linking ran, "
                + "SKIPPED when automatic linking is disabled. A storage outage while linking is not a "
                + "status here - the call answers 503 storage-unavailable instead.")
        SedmlLinkStatus linkStatus,
        @Schema(description = "Counters of the automatic link run; null when the link step did not run.")
        SedmlLinkingResponse links
) {
    public enum SedmlLinkStatus {
        COMPLETED,
        SKIPPED
    }

    public static SedmlIndexResponse from(IndexedModel indexedModel, SedmlLinkStatus status, SedmlLinkingResult links) {
        return new SedmlIndexResponse(
                indexedModel.identifier().uid(),
                indexedModel.identifier().fileId(),
                indexedModel.source().reference().sourceUri(),
                status,
                links == null ? null : SedmlLinkingResponse.from(links)
        );
    }
}

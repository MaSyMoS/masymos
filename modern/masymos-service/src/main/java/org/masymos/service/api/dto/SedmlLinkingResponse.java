package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.masymos.core.model.SedmlLinkingResult;

@Schema(description = "Counters describing one SED-ML linking run.")
public record SedmlLinkingResponse(
        @Schema(description = "Number of model reference links created.", example = "3")
        long modelReferenceLinks,
        @Schema(description = "Number of SBML observation links created.", example = "5")
        long sbmlObservationLinks,
        @Schema(description = "Number of CellML observation links created.", example = "0")
        long cellmlObservationLinks
) {
    public static SedmlLinkingResponse from(SedmlLinkingResult result) {
        return new SedmlLinkingResponse(
                result.modelReferenceLinks(),
                result.sbmlObservationLinks(),
                result.cellmlObservationLinks()
        );
    }
}

package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.masymos.core.query.ModelVersionHit;

@Schema(description = "One matching version inside a version-grouped model result.")
public record ModelVersionHitResponse(
        @Schema(description = "UID of the matched version in the graph.", example = "model_0001")
        String uid,
        @Schema(
                description = "External version label of the matched version; null when the source carried none.",
                example = "2014-01-01")
        String versionId,
        @Schema(description = "Relevance score of this version for the query.", example = "0.72")
        double score
) {
    public static ModelVersionHitResponse from(ModelVersionHit hit) {
        return new ModelVersionHitResponse(hit.uid(), hit.versionId(), hit.score());
    }
}

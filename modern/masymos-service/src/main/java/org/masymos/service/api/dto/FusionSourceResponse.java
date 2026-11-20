package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.masymos.core.query.fusion.FusionSource;

@Schema(description = "One ranker that returned a fused row: which ranker, at which rank, with which native score.")
public record FusionSourceResponse(
        @Schema(
                description = "Ranker that returned the row; one of model, annotation, person, publication.",
                example = "model")
        String type,
        @Schema(description = "1-based rank of the row in that ranker after collation.", example = "2")
        int rank,
        @Schema(
                description =
                        "Native score of that ranker's hit, on the ranker's own scale and not comparable with the fused score or with another ranker's score.",
                example = "1.73")
        double score
) {
    public static FusionSourceResponse from(FusionSource source) {
        return new FusionSourceResponse(source.ranker().wire(), source.rank(), source.score());
    }
}

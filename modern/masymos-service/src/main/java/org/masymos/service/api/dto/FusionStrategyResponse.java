package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A rank-fusion strategy and what its fused score means.")
public record FusionStrategyResponse(
        @Schema(description = "Strategy id to send as the strategy field of an aggregated query.", example = "bestRank")
        String id,
        @Schema(
                description =
                        "Whether the strategy produces a meaningful fusedScore. When false, fusedScore is null and only the order is defined.",
                example = "false")
        boolean producesScore,
        @Schema(
                description = "One-line description of what the strategy does and on which scale it scores.",
                example = "Orders rows by their best rank across the rankers without computing a score.")
        String description
) {}

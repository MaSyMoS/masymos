package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import org.masymos.core.query.fusion.FusedModelHit;
import org.masymos.core.query.fusion.ModelRow;

@Schema(
        description =
                "One fused row: a model file with the document facts of its representative version, the fused"
                        + " score and the per-ranker provenance that led to it.")
public record AggregatedHitResponse(
        @Schema(description = "UID of the representative model version in the graph.", example = "model_0001")
        String uid,
        @Schema(description = "Model file identifier; one row per model file.", example = "BIOMD0000000001.xml")
        String fileId,
        @Schema(description = "Model identifier as given in the model file.", example = "BIOMD0000000001")
        String modelId,
        @Schema(description = "Human-readable model name.", example = "Example model")
        String modelName,
        @Schema(
                description = "URI of the model document; null when the row carries no source URI.",
                example = "file:///path/to/model.sbml")
        URI documentUri,
        @Schema(
                description = "File name of the model document; carries the same value as fileId.",
                example = "BIOMD0000000001.xml")
        String filename,
        @Schema(description = "External version label of the representative version; null when it carried none.",
                example = "v2")
        String versionId,
        @Schema(description = "Whether the source document is stored in the graph.", example = "true")
        boolean documentStored,
        @Schema(
                description =
                        "Original size of the stored document in bytes; null when nothing is stored.",
                example = "20480")
        Long documentBytes,
        @Schema(description = "Whether the stored copy of the document is truncated.", example = "false")
        boolean documentTruncated,
        @Schema(
                description =
                        "Score on the producing strategy's own scale: 0 < fusedScore <= 1 for bordaCombMnz, null"
                                + " for the order-only bestRank. Not comparable with per-entity scores, legacy scores,"
                                + " other strategies or other requests.",
                example = "0.31")
        Double fusedScore,
        @Schema(
                description =
                        "Provenance in the fixed order model, annotation, person, publication; only the rankers that returned the row appear.",
                example = "[{\"type\": \"model\", \"rank\": 2, \"score\": 1.73}]")
        List<FusionSourceResponse> sources
) {
    public AggregatedHitResponse {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static AggregatedHitResponse from(FusedModelHit hit) {
        ModelRow row = hit.row();
        return new AggregatedHitResponse(
                row.uid(),
                row.fileId(),
                row.modelId(),
                row.modelName(),
                row.documentUri(),
                row.fileId(),
                row.versionId(),
                row.documentStored(),
                row.documentBytes(),
                row.documentTruncated(),
                hit.fusedScore(),
                hit.sources().stream().map(FusionSourceResponse::from).toList()
        );
    }
}

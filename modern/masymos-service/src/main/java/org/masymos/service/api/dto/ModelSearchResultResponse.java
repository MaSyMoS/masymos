package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.QueryField;

@Schema(description = "A model matched by a query.")
public record ModelSearchResultResponse(
        @Schema(description = "Model UID in the graph.", example = "model_0001")
        String uid,
        @Schema(description = "Model identifier as given in the model file.", example = "BIOMD0000000001")
        String modelId,
        @Schema(description = "Human-readable model name.", example = "Example model")
        String modelName,
        @Schema(description = "URI of the model document.",
                example = "file:///path/to/model.sbml")
        URI documentUri,
        @Schema(description = "File name of the model document.", example = "example.xml")
        String filename,
        @Schema(description = "Relevance score of the match.", example = "0.87")
        double score,
        @Schema(
                description =
                        "Matching versions of a version-grouped result, highest score first; empty for an ungrouped result.")
        List<ModelVersionHitResponse> versions,
        @Schema(description = "External version label of the hit; null when the source carried none.", example = "v2")
        String versionId,
        @Schema(
                description =
                        "Request fields the hit answered; request provenance, not a per-hit explanation. Names the"
                                + " fields the caller constrained (plus KEYWORD when the free-text keyword was used).",
                example = "[\"NAME\", \"KEYWORD\"]")
        List<String> matchedFields,
        @Schema(
                description =
                        "Index the hit came from: modelFulltextV5 for a full-text hit, exact for an exact-only hit.",
                example = "modelFulltextV5")
        String indexSource,
        @Schema(description = "Whether the source document is stored in the graph.", example = "true")
        boolean documentStored,
        @Schema(
                description =
                        "Original size of the stored document in bytes; null when nothing is stored. The bytes themselves"
                                + " stay behind the document download endpoint.",
                example = "20480")
        Long documentBytes,
        @Schema(description = "Whether the stored copy of the document is truncated.", example = "false")
        boolean documentTruncated
) {
    public ModelSearchResultResponse {
        versions = versions == null ? List.of() : List.copyOf(versions);
        matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
    }

    public static ModelSearchResultResponse from(ModelSearchResult result) {
        return new ModelSearchResultResponse(
                result.uid(),
                result.modelId(),
                result.modelName(),
                result.documentUri(),
                result.filename(),
                result.score(),
                result.versions().stream().map(ModelVersionHitResponse::from).toList(),
                result.versionId(),
                result.matchedFields().stream().map(QueryField::name).toList(),
                result.indexSource(),
                result.documentStored(),
                result.documentBytes(),
                result.documentTruncated()
        );
    }
}

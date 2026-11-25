package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.masymos.core.model.ModelDeletionResult;

@Schema(description = "What a delete removed. Legacy reported a node and relationship count; the relationship count is not reproduced.")
public record ModelDeletionResponse(
        @Schema(description = "Documents (versions) removed.") int documents,
        @Schema(description = "Nodes removed, including annotation nodes purged because nothing referenced them.") long nodes
) {
    public static ModelDeletionResponse from(ModelDeletionResult result) {
        return new ModelDeletionResponse(result.documents(), result.nodes());
    }
}

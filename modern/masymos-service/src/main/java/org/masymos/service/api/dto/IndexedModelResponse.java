package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import org.masymos.core.extract.IndexedModel;

@Schema(description = "Identifier of a model that was indexed.")
public record IndexedModelResponse(
        @Schema(description = "Generated model UID in the graph.", example = "model_0001")
        String uid,
        @Schema(description = "File identifier of the indexed model.", example = "BIOMD0000000001")
        String fileId,
        @Schema(description = "Source URI the model was read from.",
                example = "file:///path/to/model.sbml")
        URI sourceUri
) {
    public static IndexedModelResponse from(IndexedModel indexedModel) {
        return new IndexedModelResponse(
                indexedModel.identifier().uid(),
                indexedModel.identifier().fileId(),
                indexedModel.source().reference().sourceUri()
        );
    }
}

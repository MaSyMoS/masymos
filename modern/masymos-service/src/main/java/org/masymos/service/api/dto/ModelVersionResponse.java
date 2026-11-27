package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.masymos.core.model.ModelVersion;

@Schema(description = "One stored version of a model file.")
public record ModelVersionResponse(
        @Schema(description = "Model UID in the graph.", example = "model_0001")
        String uid,
        @Schema(description = "File identifier of the model.", example = "BIOMD0000000001")
        String fileId,
        @Schema(description = "External version label; absent when the source carried none.", example = "2014-01-01")
        String versionId,
        @Schema(description = "Numeric legacy MaSyMoS identifier; still accepted by lookups.", example = "42")
        Long legacyUid
) {
    public static ModelVersionResponse from(ModelVersion version) {
        return new ModelVersionResponse(
                version.uid(), version.fileId(), version.versionId(), version.legacyUid());
    }
}

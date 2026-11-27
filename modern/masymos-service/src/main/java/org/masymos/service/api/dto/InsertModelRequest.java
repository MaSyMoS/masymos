package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Map;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

@Schema(description = "Reference to a model to insert into the graph.")
public record InsertModelRequest(
        @NotBlank @Schema(description = "File identifier of the model.", example = "BIOMD0000000001")
        String fileId,
        @NotNull @Schema(description = "Source location of the model.",
                example = "file:///path/to/model.sbml")
        URI url,
        @NotNull @Schema(description = "Model format.", example = "SBML")
        ModelType modelType,
        @Schema(description = "External version label of the document; opaque, may be absent.", example = "2014-01-01")
        String versionId,
        @Schema(description = "Free-form metadata stored with the document (legacy META).")
        Map<String, String> metadata,
        @Schema(description = "Refuse the insert when a document with this fileId already exists "
                + "(legacy enforceUniqueFileID). Off by default: a repeated fileId is a new version.")
        Boolean enforceUniqueFileId
) {
    public ModelReference toModelReference() {
        return new ModelReference(fileId, url, modelType, versionId, metadata);
    }

    public boolean enforcesUniqueFileId() {
        return Boolean.TRUE.equals(enforceUniqueFileId);
    }
}

package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;

@Schema(description = "Request to index one or more CellML models.")
public record CellmlIndexModelsRequest(
        @NotEmpty @Schema(description = "URIs of the CellML files to index.",
                example = "[\"file:///path/to/model.cellml\"]")
        List<@NotNull URI> sources,
        @Schema(description = "External cMeta lookup mode: 'none' or 'pmr'; defaults to 'none'.", example = "none")
        String externalCmeta
) {
    public String externalCmetaOrDefault() {
        if (externalCmeta == null || externalCmeta.isBlank()) {
            return "none";
        }
        return externalCmeta;
    }
}

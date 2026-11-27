package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import org.masymos.core.query.SedmlSearchResult;

@Schema(description = "A matched SED-ML description with the model sources it references.")
public record SedmlSearchResultResponse(
        @Schema(description = "SED-ML document UID in the graph.", example = "sedml_0001")
        String uid,
        @Schema(description = "File name of the SED-ML document.", example = "experiment.sedml")
        String fileId,
        @Schema(description = "Name of the SED-ML description.", example = "Glycolysis experiment")
        String modelName,
        @Schema(description = "URI of the SED-ML document.",
                example = "file:///path/to/experiment.sedml")
        URI documentUri,
        @Schema(description = "External version label of the hit; null when the source carried none.", example = "v2")
        String versionId,
        @Schema(description = "Relevance score of the match; a SED-ML score is not comparable to a model score.",
                example = "0.87")
        double score,
        @Schema(
                description =
                        "Model sources the description references, de-duplicated: the resolved source when it could"
                                + " be resolved, otherwise the declared one.",
                example = "[\"urn:miriam:biomodels.db:BIOMD0000000001\"]")
        List<String> referencedModelUris
) {
    public SedmlSearchResultResponse {
        referencedModelUris = referencedModelUris == null ? List.of() : List.copyOf(referencedModelUris);
    }

    public static SedmlSearchResultResponse from(SedmlSearchResult result) {
        return new SedmlSearchResultResponse(
                result.uid(),
                result.fileId(),
                result.modelName(),
                result.documentUri(),
                result.versionId(),
                result.score(),
                result.referencedModelUris().stream().map(URI::toString).toList()
        );
    }
}

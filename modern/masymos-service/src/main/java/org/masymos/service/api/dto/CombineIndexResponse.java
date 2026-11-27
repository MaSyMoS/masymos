package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import org.masymos.core.archive.CombineArchiveIndexingService.CombineArchiveIndexingResult;

@Schema(description = "Result of indexing the model and simulation description entries of one COMBINE archive (OMEX).")
public record CombineIndexResponse(
        @Schema(description = "URI of the indexed archive.",
                example = "file:///path/to/archive.omex")
        URI sourceUri,
        @Schema(description = "Number of archive entries that were materialised and handed to an indexing service.",
                example = "2")
        int indexedEntries,
        @ArraySchema(arraySchema = @Schema(description = "Models indexed from the archive."))
        List<IndexedModelResponse> models,
        @ArraySchema(arraySchema = @Schema(description = "Archive entries that were not indexed, as "
                        + "location: reason, the reason naming the raw format URI.",
                example = "[\"metadata.rdf: unsupported entry kind OTHER (format "
                        + "http://identifiers.org/combine.specifications/omex-metadata)\"]"))
        List<String> skipped
) {
    public static CombineIndexResponse from(URI sourceUri, CombineArchiveIndexingResult result) {
        return new CombineIndexResponse(
                sourceUri,
                result.indexedEntries(),
                result.models().stream().map(IndexedModelResponse::from).toList(),
                List.copyOf(result.skipped())
        );
    }
}

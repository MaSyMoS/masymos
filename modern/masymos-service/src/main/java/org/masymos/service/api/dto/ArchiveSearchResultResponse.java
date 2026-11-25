package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import org.masymos.core.query.ArchiveSearchResult;

@Schema(description = "A matched COMBINE archive with the number of documents it bundles.")
public record ArchiveSearchResultResponse(
        @Schema(description = "Archive UID in the graph; its source URI when the archive carries none.",
                example = "https://example.org/archive.omex")
        String uid,
        @Schema(description = "File name of the archive.", example = "archive.omex")
        String fileId,
        @Schema(description = "Source URI of the archive.", example = "https://example.org/archive.omex")
        URI sourceUri,
        @Schema(description = "Relevance score of the match; not comparable to per-entity scores.", example = "1.0")
        double score,
        @Schema(description = "Number of documents linked to the archive; 0 for an archive without documents.",
                example = "2")
        long documentCount
) {
    public static ArchiveSearchResultResponse from(ArchiveSearchResult result) {
        return new ArchiveSearchResultResponse(
                result.uid(),
                result.fileId(),
                result.sourceUri(),
                result.score(),
                result.documentCount()
        );
    }
}

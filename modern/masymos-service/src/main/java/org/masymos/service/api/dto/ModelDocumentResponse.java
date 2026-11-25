package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.masymos.core.model.StoredDocument;

@Schema(description = "One stored model document, including what was captured for download.")
public record ModelDocumentResponse(
        @Schema(description = "Canonical identifier of the document.") String uid,
        @Schema(description = "File identifier of the model.", example = "BIOMD0000000001") String fileId,
        @Schema(description = "External version label; absent when the source carried none.") String versionId,
        @Schema(description = "Numeric identifier legacy MaSyMoS minted for this document.") Long legacyUid,
        @Schema(description = "Free-form metadata stored with the document.") Map<String, String> metadata,
        @Schema(description = "Whether a source document was captured when it was indexed.") boolean hasDocument,
        @Schema(description = "Size of the source document before any truncation, in bytes.") long originalBytes,
        @Schema(description = "Whether the stored copy is shorter than the original.") boolean truncated
) {
    public static ModelDocumentResponse from(StoredDocument document) {
        return new ModelDocumentResponse(
                document.uid(),
                document.fileId(),
                document.versionId(),
                document.legacyUid(),
                document.metadata(),
                document.hasContent(),
                document.originalBytes(),
                document.truncated());
    }
}

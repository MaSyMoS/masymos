package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import org.masymos.service.indexing.SourceOutcome;

/**
 * What one source of a failed index request did (task {@code C04-1}, finding {@code GAP02-002b}).
 *
 * <p>It is the {@code outcomes} property of the {@code 502 partial-index} problem detail, and it exists
 * because an index request is not atomic across its sources: the documents before the failing one are
 * written, and this array is the only place that says so (decision 49). Every source of the request
 * appears exactly once, in request order.
 */
@Schema(description = "Outcome of one source in an index request that did not complete.")
public record SourceOutcomeResponse(
        @Schema(description = "The source URI exactly as the request named it.",
                example = "file:///path/to/model.sbml")
        URI sourceUri,
        @Schema(description = "INDEXED, FAILED or NOT_ATTEMPTED - the last only when a store outage ended "
                        + "the run before that source's turn.",
                example = "INDEXED")
        String status,
        @ArraySchema(arraySchema = @Schema(description = "Identifiers of the models this source wrote; "
                + "empty unless it was indexed, and legitimately empty for an archive that carried only "
                + "metadata entries."))
        List<IndexedDocumentResponse> documents,
        @Schema(description = "Why the source failed, null unless it failed; never a driver or store "
                        + "message.",
                example = "ModelParseException: unsupported SBML namespace ...")
        String detail
) {
    /** One written document, by the two identifiers the successful index response also publishes. */
    @Schema(description = "Identifier of one model written by an indexed source.")
    public record IndexedDocumentResponse(
            @Schema(description = "Generated model UID in the graph.", example = "model_0001")
            String uid,
            @Schema(description = "File identifier of the indexed model.", example = "BIOMD0000000001")
            String fileId
    ) {
        static IndexedDocumentResponse from(SourceOutcome.IndexedDocument document) {
            return new IndexedDocumentResponse(document.uid(), document.fileId());
        }
    }

    public static SourceOutcomeResponse from(SourceOutcome outcome) {
        return new SourceOutcomeResponse(
                outcome.sourceUri(),
                outcome.status().name(),
                outcome.documents().stream().map(IndexedDocumentResponse::from).toList(),
                outcome.detail());
    }
}

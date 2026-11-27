package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.masymos.core.rederive.ReDeriveReport;

/**
 * The JSON shape of one re-derive run ({@code OPN-7}). A read-only scan writes nothing and reports
 * {@code applied} zero, so a caller can tell a scan from a repair by that counter alone.
 *
 * <p>{@code missingByLabel} is the label set as the writer groups it (sorted label names, joined by
 * {@code :}), which is the vocabulary the repair will use; it is empty when no document is short a
 * node.
 */
@Schema(description = "What one re-derive run found over the already indexed documents, and - with apply=true - what it repaired.")
public record ReDeriveReportResponse(
        @Schema(description = "Number of documents with a stored source this run looked at.", example = "120")
        int documentsScanned,
        @Schema(description = "Documents whose graph misses nodes the current mapper yields, or whose nodes "
                + "predate derivedKey.", example = "104")
        int documentsStale,
        @Schema(description = "Documents whose graph already holds everything the mapper yields.", example = "16")
        int documentsCurrent,
        @Schema(description = "Documents with a stored source that this run's limit left for a later run; "
                + "0 when the run was unbounded.", example = "0")
        int documentsNotScanned,
        @Schema(description = "Documents skipped because the graph stores no source document for them.", example = "2")
        int skippedWithoutStoredDocument,
        @Schema(description = "Documents skipped because the stored source document is truncated.", example = "1")
        int skippedTruncatedDocument,
        @Schema(description = "Documents skipped because this scan does not re-parse their model type.", example = "3")
        int skippedUnsupportedType,
        @Schema(description = "Documents skipped because the stored source document did not parse.", example = "0")
        int skippedUnparseable,
        @Schema(description = "Owned nodes over the page that carry no derivedKey, so a repair could not "
                + "recognise them.", example = "812")
        int missingDerivedKeys,
        @Schema(description = "Node shortfall merged over the page, keyed by the sorted label set.", example = "{\"ANNOTATION\":4,\"Model:SBML_MODEL\":0}")
        Map<String, Integer> missingByLabel,
        @Schema(description = "Position to pass back as `cursor` to read the next page; absent when this run reached the end.", example = "3f2c8a6e-1b4d-4c31-9a02-5c7d1e8f4b60")
        String nextCursor,
        @Schema(description = "True when this run started from a cursor, so it did not read the documents before it.", example = "false")
        boolean startedFromCursor,
        @Schema(description = "Documents this run actually repaired - their derived graph was rewritten from "
                + "the stored source; 0 for a read-only scan.", example = "9")
        int applied
) {
    public static ReDeriveReportResponse from(ReDeriveReport report) {
        return new ReDeriveReportResponse(
                report.examined(),
                report.stale(),
                report.current(),
                report.remaining(),
                report.documentsWithoutSource(),
                report.truncated(),
                report.unsupported(),
                report.unparseable(),
                report.missingDerivedKeys(),
                report.missingByLabel(),
                report.nextCursor(),
                report.resumed(),
                report.applied());
    }
}

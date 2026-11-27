package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.masymos.core.units.UnitBackfillReport;

/**
 * The JSON shape of one unit-backfill run (STO-6).
 *
 * <p>The field names are the record's component names, unchanged, because a caller compares runs.
 * {@link #writes()} and {@link #complete()} are carried as explicit members rather than left as
 * derived getters: they are the two values the endpoint exists to report — whether anything was
 * written (a dry run writes nothing) and whether the corpus was fully covered.
 */
@Schema(description = "Counters describing one unit-backfill run over the already indexed documents.")
public record UnitBackfillReportResponse(
        @Schema(description = "True when the run wrote; false for a dry run.", example = "false")
        boolean applied,
        @Schema(description = "Number of documents the run looked at.", example = "120")
        int documentsScanned,
        @Schema(description = "Documents with a stored source that this run's limit left for a later run; "
                + "0 when the run was unbounded.", example = "0")
        int documentsNotScanned,
        @Schema(description = "Number of documents whose unit layer was brought up to date.", example = "18")
        int documentsBackfilled,
        @Schema(description = "Number of documents that already carried a complete unit layer.", example = "98")
        int documentsAlreadyComplete,
        @Schema(description = "Documents skipped because the graph stores no source document for them.", example = "2")
        int skippedWithoutStoredDocument,
        @Schema(description = "Documents skipped because the stored source document is truncated.", example = "1")
        int skippedTruncatedDocument,
        @Schema(description = "Documents skipped because their media type is not supported.", example = "1")
        int skippedUnsupportedType,
        @Schema(description = "Documents skipped because the stored source document did not parse.", example = "0")
        int skippedUnparseable,
        @Schema(description = "UNIT nodes created.", example = "40")
        int unitsCreated,
        @Schema(description = "USES_UNIT relationships created.", example = "12")
        int usesUnitCreated,
        @Schema(description = "HAS_UNIT relationships created.", example = "6")
        int hasUnitCreated,
        @Schema(description = "COMPOSED_OF relationships created.", example = "3")
        int compositionCreated,
        @Schema(description = "Relationships the run left alone, e.g. already present.", example = "0")
        int relationshipsSkipped,
        @Schema(description = "Sum of the created nodes and relationships; 0 on a dry run and on a rerun.", example = "61")
        int writes,
        @Schema(description = "True when no document had to be skipped and the run read the population from its beginning; false when the corpus was not fully covered.", example = "false")
        boolean complete,
        @Schema(description = "Position to pass back as `cursor` to read the next page; absent when this run reached the end.", example = "3f2c8a6e-1b4d-4c31-9a02-5c7d1e8f4b60")
        String nextCursor,
        @Schema(description = "True when this run started from a cursor, so it did not read the documents before it.", example = "false")
        boolean startedFromCursor
) {
    public static UnitBackfillReportResponse from(UnitBackfillReport report) {
        return new UnitBackfillReportResponse(
                report.applied(),
                report.documentsScanned(),
                report.documentsNotScanned(),
                report.documentsBackfilled(),
                report.documentsAlreadyComplete(),
                report.skippedWithoutStoredDocument(),
                report.skippedTruncatedDocument(),
                report.skippedUnsupportedType(),
                report.skippedUnparseable(),
                report.unitsCreated(),
                report.usesUnitCreated(),
                report.hasUnitCreated(),
                report.compositionCreated(),
                report.relationshipsSkipped(),
                report.writes(),
                report.complete(),
                report.nextCursor(),
                report.startedFromCursor()
        );
    }
}

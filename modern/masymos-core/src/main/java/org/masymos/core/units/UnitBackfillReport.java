package org.masymos.core.units;

/**
 * What one unit-backfill run did (STO-6), shaped for a report a human reads and a test can assert on.
 *
 * <p>Two deliberate design points. First, {@link #writes()} has to be zero on a second run: that is
 * the idempotency criterion, and it is why the counters distinguish "merged" from "created". Second,
 * the skip counters are separate fields rather than one "skipped" number, because a run over a real
 * corpus has to be able to say <em>why</em> a document was left alone - and a run that skipped
 * anything must never present itself as a complete corpus ({@link #complete()}).
 */
public record UnitBackfillReport(
        boolean applied,
        int documentsScanned,
        int documentsBackfilled,
        int documentsAlreadyComplete,
        int skippedWithoutStoredDocument,
        int skippedTruncatedDocument,
        int skippedUnsupportedType,
        int skippedUnparseable,
        int unitsCreated,
        int usesUnitCreated,
        int hasUnitCreated,
        int compositionCreated,
        int relationshipsSkipped) {

    /** The number of graph writes this run performed; a second identical run reports {@code 0}. */
    public int writes() {
        return unitsCreated + usesUnitCreated + hasUnitCreated + compositionCreated;
    }

    /** True when nothing had to be left alone: every document was either complete or backfilled. */
    public boolean complete() {
        return skippedWithoutStoredDocument == 0
                && skippedTruncatedDocument == 0
                && skippedUnsupportedType == 0
                && skippedUnparseable == 0;
    }

    public static UnitBackfillReport empty(boolean applied) {
        return new UnitBackfillReport(applied, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}

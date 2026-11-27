package org.masymos.core.units;

/**
 * What one unit-backfill run did (STO-6), shaped for a report a human reads and a test can assert on.
 *
 * <p>Two deliberate design points. First, {@link #writes()} has to be zero on a second run: that is
 * the idempotency criterion, and it is why the counters distinguish "merged" from "created". Second, the
 * skip counters are separate fields rather than one "skipped" number, because a run over a real
 * corpus has to be able to say <em>why</em> a document was left alone - and a run that skipped
 * anything must never present itself as a complete corpus ({@link #complete()}).
 *
 * <p>Since {@code T-018} a run is bounded, so the counters have to separate "did not look" from "looked
 * and could not": {@link #documentsScanned()} is what the run looked at, {@link #documentsNotScanned()}
 * is what its {@code limit} kept out, and a run that left documents for a later run is not
 * {@link #complete()} either. The two are different answers to different questions, and a run that
 * conflated them would claim a corpus it never read.
 *
 * <p>Since {@code C04-2} the position the run stopped at travels as {@link #nextCursor()}, and the
 * position it started from is answered by {@link #startedFromCursor()}: a run that resumed is never
 * {@link #complete()}, because the documents before its cursor were another run's work.
 */
public record UnitBackfillReport(
        boolean applied,
        int documentsScanned,
        int documentsNotScanned,
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
        int relationshipsSkipped,
        String nextCursor,
        boolean startedFromCursor) {

    /** The number of graph writes this run performed; a second identical run reports {@code 0}. */
    public int writes() {
        return unitsCreated + usesUnitCreated + hasUnitCreated + compositionCreated;
    }

    /**
     * True when this run read the whole population from its beginning and nothing had to be left
     * alone: every document with a stored source was either complete or backfilled, and every document
     * without one is counted rather than ignored. A resumed run is never complete - it did not read the
     * head, so it cannot speak for the corpus.
     */
    public boolean complete() {
        return !startedFromCursor
                && nextCursor == null
                && documentsNotScanned == 0
                && skippedWithoutStoredDocument == 0
                && skippedTruncatedDocument == 0
                && skippedUnsupportedType == 0
                && skippedUnparseable == 0;
    }

    public static UnitBackfillReport empty(boolean applied) {
        return new UnitBackfillReport(applied, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, false);
    }
}

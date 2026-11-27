package org.masymos.core.spi;

import org.masymos.core.rederive.ReDeriveReport;

/**
 * The read-only half of {@code OPN-7}: for a bounded page of documents with a stored source, re-parse
 * that source, run the current mapper, and report what the graph does not hold.
 *
 * <p>Why a scan before a writer. The write path creates its nodes ({@code ParsedModelGraphCypher}
 * {@code CREATE}), so it cannot repair an existing document, and a repair needs an identity to merge
 * on. Since 2026-11-26 the write path stamps {@code derivedKey} (the mapper's own graph key) on every
 * node it owns, which gives the writer that identity - but every document indexed before that date
 * lacks it, so a repair cannot start until someone has measured which documents are affected and how.
 * This scan is that measurement; it writes nothing.
 *
 * <p>The bound and the cursor are the {@code C04-2} contract the unit backfill uses: at most
 * {@code limit} documents per run, and {@link ReDeriveReport#nextCursor()} is the position to resume
 * after. The cursor is a keyset on the document {@code uid}, so a dry scan's cursor starts an applied
 * run at the same place.
 */
public interface ReDerive {
    /**
     * @param limit the maximum number of documents with a stored source this run looks at;
     *     {@link ReDeriveReport#UNBOUNDED} for no bound
     * @param cursor where to start: {@code null} for the beginning, otherwise a previous run's
     *     {@link ReDeriveReport#nextCursor()}
     */
    ReDeriveReport run(int limit, String cursor);

    /** A scan bounded by {@code limit}, from the beginning of the population. */
    default ReDeriveReport run(int limit) {
        return run(limit, null);
    }

    /** A scan bounded by {@link ReDeriveReport#DEFAULT_LIMIT}, from the beginning. */
    default ReDeriveReport run() {
        return run(ReDeriveReport.DEFAULT_LIMIT, null);
    }

    /**
     * The same bounded sweep, repairing what it finds ({@code OPN-7}'s second slice): every document the
     * scan calls stale has its derived graph re-written from the stored source, in one transaction per
     * document, and the document keeps its {@code uid}, {@code versionId}, chain position and stored
     * bytes - a repair writes no new version.
     *
     * <p>A document whose stored source is truncated is <strong>skipped</strong> with that reason rather
     * than partially rewritten, and the report counts it as {@code truncated}. A document the scan calls
     * {@code current} is not touched, so a second run over the same page applies nothing.
     *
     * <p>A store that cannot repair must not pretend it did: the default throws.
     *
     * @throws UnsupportedOperationException from an implementation that has no writer
     */
    default ReDeriveReport apply(int limit, String cursor) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot repair a derived graph");
    }

    /** An applied sweep bounded by {@code limit}, from the beginning of the population. */
    default ReDeriveReport apply(int limit) {
        return apply(limit, null);
    }
}

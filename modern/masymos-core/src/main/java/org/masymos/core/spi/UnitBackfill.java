package org.masymos.core.spi;

import org.masymos.core.units.UnitBackfillReport;

/**
 * The unit backfill of {@code STO-6}: brings documents indexed before CML-1 up to the current unit
 * layer, from the source document the graph already stores ({@code xmlDoc}, STO-2) - never by
 * re-indexing, which would append a version to the chain and needs the original source (decision 36).
 *
 * <p>The contract a caller can rely on: a run writes <em>only</em> the unit layer ({@code UNIT} nodes,
 * {@code COMPOSED_OF}, {@code USES_UNIT}, {@code HAS_UNIT}); it never creates or changes a
 * {@code Document}, a {@code Model}, a version link, an entity, an annotation, or {@code ownerUid}; and
 * a second run over the same graph writes nothing ({@link UnitBackfillReport#writes()} is
 * {@code 0}). Documents the run had to skip are counted by reason, so a caller can tell "nothing left
 * to do" from "I could not look".
 *
 * <p>Since {@code T-018} a run is also <em>bounded</em>, and since {@code C04-2} it is
 * <em>resumable</em>: {@link #run(boolean, int, String)} looks at at most {@code limit} documents with
 * a stored source and reports the position it stopped at as {@link UnitBackfillReport#nextCursor()};
 * pass that value back as {@code cursor} and the next run continues after it. A whole corpus is
 * therefore swept in bounded requests, each of which fits in a request timeout, instead of one request
 * that holds a thread for as long as the corpus takes.
 *
 * <p>The cursor is a keyset on the document {@code uid} - the one property the graph constrains to be
 * unique - so it is a <em>position in the population</em>, not a count of work already done: a dry
 * run's cursor starts an applied run at the same place, and a document indexed during a sweep can land
 * behind the cursor. That is harmless: such a document was just written, so its unit layer is already
 * current, and the next sweep from the beginning sees it.
 */
public interface UnitBackfill {
    /** The default bound of one run: 500 documents with a stored source. */
    int DEFAULT_LIMIT = 500;

    /** {@code limit} value meaning "no bound"; a whole-corpus run from a worker thread, not from a request. */
    int UNBOUNDED = 0;

    /**
     * @param apply {@code false} reports what would be written without writing anything (the default
     *     a caller should use first); {@code true} performs the writes
     * @param limit the maximum number of documents with a stored source this run looks at; {@link
     *     #UNBOUNDED} for no bound
     * @param cursor where to start: {@code null} for the beginning of the population, otherwise the
     *     {@link UnitBackfillReport#nextCursor()} of a previous run
     */
    UnitBackfillReport run(boolean apply, int limit, String cursor);

    /** A run bounded by {@code limit}, from the beginning of the population. */
    default UnitBackfillReport run(boolean apply, int limit) {
        return run(apply, limit, null);
    }

    /** A run bounded by {@link #DEFAULT_LIMIT}, from the beginning of the population. */
    default UnitBackfillReport run(boolean apply) {
        return run(apply, DEFAULT_LIMIT, null);
    }
}

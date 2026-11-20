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
 */
public interface UnitBackfill {
    /**
     * @param apply {@code false} reports what would be written without writing anything (the default
     *     a caller should use first); {@code true} performs the writes
     */
    UnitBackfillReport run(boolean apply);
}

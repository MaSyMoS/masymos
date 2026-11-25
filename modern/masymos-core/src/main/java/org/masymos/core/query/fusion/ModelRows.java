package org.masymos.core.query.fusion;

import java.util.List;

/**
 * What one document-URI to model-row lookup answered: the rows, and whether the lookup stopped at
 * its own bound while more rows matched ({@code REL-007}, deferred as {@code C03-2} until
 * 2026-11-23).
 *
 * <p>{@code truncated} is the only way a caller can tell a complete answer from a cut one. The bound
 * is the lookup's own ({@code Neo4jModelRowLookup.MAX_LOOKUP_ROWS}), so a caller cannot re-derive
 * it from the request: a store that carries five versions per URI cuts a 500-URI request at 2 000
 * rows, and without this flag the fused list would simply be shorter with nothing saying why. A
 * lookup implementation that cannot truncate - a fake, or a store with no bound - answers {@code
 * false}, because a bound it does not have never cut anything.
 *
 * <p>{@code rows} is held by a defensive copy and never {@code null}.
 */
public record ModelRows(List<ModelRow> rows, boolean truncated) {
    public ModelRows {
        rows = rows == null ? List.<ModelRow>of() : List.copyOf(rows);
    }

    /** The answer of a lookup that resolved nothing, and therefore cut nothing. */
    public static ModelRows empty() {
        return new ModelRows(List.of(), false);
    }
}

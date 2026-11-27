package org.masymos.core.query;

import java.util.List;

/**
 * One keyset page ({@code OPN-6}): the hits the page answered and the position to continue from, or
 * {@code null} when the page reached the end.
 *
 * <p>{@code nextCursor} is minted only when the store proved there is more: the statement asks for one row
 * beyond {@code top} and the probe row is dropped. That is the rule the ranker depth already uses
 * ({@code C03-5}) - a short answer never carries a cursor, so "no cursor" means "nothing left", not "the
 * service did not look".
 */
public record Page<T>(List<T> hits, String nextCursor) {
    public Page {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /** A page that reached the end. */
    public static <T> Page<T> last(List<T> hits) {
        return new Page<>(hits, null);
    }
}

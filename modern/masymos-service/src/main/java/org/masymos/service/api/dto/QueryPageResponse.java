package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.masymos.core.query.Page;

/**
 * One keyset page over HTTP ({@code OPN-6}): the hits, plus the cursor to continue from or {@code null}
 * when nothing follows.
 *
 * <p>The envelope exists because a cursor cannot live in a bare array, and a response header would let a
 * caller page nothing while believing it had the whole answer. {@code nextCursor} is present exactly when
 * the store proved there is more (the statement reads one row beyond {@code top} and drops it), so
 * {@code null} means "this was the end", never "the service did not look".
 *
 * <p>The cursor travels as the request parameter {@code cursor}, not in the body: it is a position in a
 * result set, and the body stays the query it always was. That also keeps the request record's shape
 * unchanged, which matters here for a measured reason - adding a component to it made javac 25 abort with
 * {@code CompilerException: NullPointerException} in this build (2026-11-26).
 */
@Schema(description = "One page of query results and the position to continue from.")
public final class QueryPageResponse<T> {
    private final List<T> hits;
    private final String nextCursor;

    public QueryPageResponse(List<T> hits, String nextCursor) {
        this.hits = hits == null ? List.of() : List.copyOf(hits);
        this.nextCursor = nextCursor;
    }

    @Schema(description = "The hits of this page.")
    public List<T> getHits() {
        return hits;
    }

    @Schema(
            description = "Opaque position to send back as `cursor` for the next page; absent when this page reached the end.",
            example = "MTIuNXxub2RlLTd8ZjFhMmIz")
    public String getNextCursor() {
        return nextCursor;
    }

    /** Maps a domain page into its wire shape, keeping the cursor as it is. */
    public static <T, R> QueryPageResponse<R> from(Page<T> page, Function<T, R> mapping) {
        return new QueryPageResponse<>(page.hits().stream().map(mapping).toList(), page.nextCursor());
    }
}

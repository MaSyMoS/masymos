package org.masymos.neo4j;

import java.util.List;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.Page;
import org.masymos.core.query.PageCursor;

/**
 * One keyset page of a resource lane ({@code OPN-6}), shared by the annotation, person, publication,
 * SED-ML and archive queries so all five continue by the same rule the model lane uses.
 *
 * <p>The rule: the statement asks for one row beyond {@code top}, so a page that fills its bound proves
 * something follows and a short page proves nothing does; the probe row is dropped and the last kept row
 * names the position. {@code nextCursor} is therefore present exactly when there is more, which is what
 * makes "no cursor" mean "nothing left" rather than "the service did not look" (the {@code C03-5} rule,
 * decision 51).
 */
final class PagedHits {
    private PagedHits() {}

    /**
     * One row of a page: the result a caller sees, the score the cursor continues from and the identity the
     * lane ordered by. A lane whose score is a constant (an exact-only query answers {@code 1.0}) still
     * carries it, so the cursor has one shape.
     */
    record Hit<T>(T result, double score, String identity) {}

    /**
     * Decodes the request's cursor and refuses one minted for another request (or another lane).
     *
     * @throws IllegalArgumentException if the cursor is not this service's, or names another request; the
     *     API turns that into a {@code 400} naming the parameter
     */
    static PageCursor cursorOf(ModelQueryRequest request, String lane) {
        return request.cursor() == null
                ? null
                : PageCursor.decode(request.cursor()).requireFingerprint(request.fingerprint(lane));
    }

    /**
     * The page of one lane, whose statement asked for {@code top + 1} rows.
     *
     * @param rows the rows the statement answered, probe row included
     * @param request the request, for its {@code top} and the fingerprint the cursor carries
     * @param lane the lane name that makes the fingerprint lane-specific, for example {@code "persons"}
     */
    static <T> Page<T> page(List<Hit<T>> rows, ModelQueryRequest request, String lane) {
        if (rows.size() <= request.top()) {
            return Page.last(rows.stream().map(Hit::result).toList());
        }
        var page = rows.subList(0, request.top());
        var last = page.get(page.size() - 1);
        var next = new PageCursor(last.score(), last.identity(), request.fingerprint(lane)).encode();
        return new Page<>(page.stream().map(Hit::result).toList(), next);
    }
}

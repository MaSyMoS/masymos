package org.masymos.core.query;

import java.util.List;
import java.util.Set;
import org.masymos.core.model.ModelType;

/**
 * One model query after validation: the free-text keyword, the clauses that refine it, the
 * requested number of results, an optional score floor, the version-grouping flag, the optional
 * kind filter and the optional unit filter. An empty {@code modelTypes} means "every kind", exactly
 * as before the filter existed; the only accepted values are the kinds that carry a label,
 * {@code SBML} and {@code CELLML}. An empty {@code units} means "no unit narrowing" (CML-1).
 *
 * <p><strong>The request-level bounds, in one table</strong> ({@code FT-001} and {@code REL-007};
 * the renderer's backstop and the row constants are named where they are enforced):
 *
 * <table border="1">
 *   <caption>Every bound a request or its expansion meets</caption>
 *   <tr><th>Bound</th><th>Value</th><th>Where</th></tr>
 *   <tr><td>{@link #MAX_KEYWORD_CHARS}</td><td>4096</td><td>here, for the keyword and every clause</td></tr>
 *   <tr><td>{@link #MAX_CLAUSES}</td><td>32</td><td>here</td></tr>
 *   <tr><td>{@link #MAX_RENDERED_QUERY_CHARS}</td><td>16384</td><td>here</td></tr>
 *   <tr><td>{@code Neo4jModelFulltextQuery.MAX_RENDERED_CLAUSE_CHARS}</td><td>16384</td>
 *       <td>the renderer, the last place that must be safe whatever the caller</td></tr>
 *   <tr><td>{@code Neo4jModelRowLookup.MAX_LOOKUP_ROWS}</td><td>2000</td><td>the row lookup</td></tr>
 *   <tr><td>{@code RankFusionQueryService.MAX_DOCUMENT_URIS_PER_RANKER}</td><td>500</td>
 *       <td>resource-to-row expansion</td></tr>
 * </table>
 *
 * <p>Over HTTP an exceeded request bound answers {@code 400}: this record rejects it in the compact
 * constructor, which is the only place that sees the keyword and the clauses together. The 32 766-byte
 * clamp of {@code IndexText} bounds one <em>term</em>; it never bounded the request.
 */
public record ModelQueryRequest(
        String keyword,
        List<QueryClause> clauses,
        int top,
        Double minScore,
        boolean groupByVersion,
        Set<ModelType> modelTypes,
        List<String> units,
        String cursor
) {
    /** The longest keyword one plain-text field accepts, in characters (not the term clamp's bytes). */
    public static final int MAX_KEYWORD_CHARS = 4096;
    /** How many clauses one request may carry. */
    public static final int MAX_CLAUSES = 32;
    /** How long the rendered query may be, counting field names and separators conservatively. */
    public static final int MAX_RENDERED_QUERY_CHARS = 16384;

    public ModelQueryRequest {
        clauses = clauses == null ? List.of() : List.copyOf(clauses);
        top = top <= 0 ? 10 : top;
        modelTypes = modelTypes == null ? Set.of() : Set.copyOf(modelTypes);
        units = units == null ? List.of() : units.stream()
                .filter(unit -> unit != null && !unit.isBlank())
                .map(String::trim)
                .toList();

        if (keyword != null && keyword.length() > MAX_KEYWORD_CHARS) {
            throw new IllegalArgumentException(
                    "keyword exceeds the " + MAX_KEYWORD_CHARS + "-character limit: " + keyword.length());
        }
        if (clauses.size() > MAX_CLAUSES) {
            throw new IllegalArgumentException("request carries " + clauses.size() + " clauses, the limit is "
                    + MAX_CLAUSES);
        }
        for (var clause : clauses) {
            if (clause.keyword().length() > MAX_KEYWORD_CHARS) {
                throw new IllegalArgumentException("the " + clause.field() + " clause keyword exceeds the "
                        + MAX_KEYWORD_CHARS + "-character limit: " + clause.keyword().length());
            }
        }
        var rendered = renderedLength(keyword, clauses);
        if (rendered > MAX_RENDERED_QUERY_CHARS) {
            throw new IllegalArgumentException("request renders to " + rendered + " characters, the limit is "
                    + MAX_RENDERED_QUERY_CHARS);
        }
    }

    /**
     * A conservative length of what the renderer will build: each clause contributes its keyword, the
     * field name it is written against and the separator and boost around it. Conservative on purpose -
     * a request rejected here is one the renderer could not have kept inside its own cap either.
     */
    private static int renderedLength(String keyword, List<QueryClause> clauses) {
        var length = keyword == null ? 0 : keyword.length();
        for (var clause : clauses) {
            length += clause.keyword().length() + clause.field().name().length() + 8;
        }
        return length;
    }

    /** A request with version grouping but without a kind filter. */
    public ModelQueryRequest(
            String keyword, List<QueryClause> clauses, int top, Double minScore, boolean groupByVersion) {
        this(keyword, clauses, top, minScore, groupByVersion, Set.of(), List.of(), null);
    }

    public ModelQueryRequest(
            String keyword,
            List<QueryClause> clauses,
            int top,
            Double minScore,
            boolean groupByVersion,
            Set<ModelType> modelTypes) {
        this(keyword, clauses, top, minScore, groupByVersion, modelTypes, List.of(), null);
    }

    /** A request without a score floor; model queries ignore the floor, see the QRY-4 design. */
    public ModelQueryRequest(String keyword, List<QueryClause> clauses, int top, Double minScore) {
        this(keyword, clauses, top, minScore, false, Set.of(), List.of(), null);
    }

    public ModelQueryRequest(String keyword, List<QueryClause> clauses, int top) {
        this(keyword, clauses, top, null, false, Set.of(), List.of(), null);
    }

    /**
     * A request with every filter and no cursor - the shape every caller had before {@code OPN-6} added
     * the eighth component, kept so a keyset stays opt-in instead of a parameter everybody passes.
     */
    public ModelQueryRequest(
            String keyword,
            List<QueryClause> clauses,
            int top,
            Double minScore,
            boolean groupByVersion,
            Set<ModelType> modelTypes,
            List<String> units) {
        this(keyword, clauses, top, minScore, groupByVersion, modelTypes, units, null);
    }

    /**
     * The same request starting after a cursor ({@code OPN-6}). The cursor travels as a request parameter
     * rather than in the JSON body: it is a position in a result set, not part of the query, and the body's
     * schema is the query's shape.
     */
    public ModelQueryRequest withCursor(String cursor) {
        return new ModelQueryRequest(
                keyword, clauses, top, minScore, groupByVersion, modelTypes, units, cursor);
    }

    /**
     * The fingerprint a cursor must carry to belong to this request ({@code OPN-6}). Every field that
     * narrows the result set is included, so a cursor replayed against a narrower request is refused
     * instead of answering a page of a result set the caller never asked for. {@code top} is part of it on
     * purpose: a page size is a request, and a sweep repeats the same one.
     *
     * <p>This is the model lane's form; every other lane names itself in {@link #fingerprint(String)},
     * because the same request body is a different result set on every query endpoint and a cursor must not
     * travel between them.
     */
    public String fingerprint() {
        return fingerprint("models");
    }

    /**
     * The same fingerprint for one named lane ({@code OPN-6}): {@code "annotations"}, {@code "persons"},
     * {@code "publications"}, {@code "sedml"} or {@code "archives"}. The lane is the first component, so a
     * cursor the model lane minted is refused by the annotation lane even when the two requests are
     * byte-identical.
     */
    public String fingerprint(String lane) {
        var clausesText = clauses.stream()
                .map(clause -> clause.field() + ":" + clause.keyword())
                .reduce("", (joined, next) -> joined + ";" + next);
        return PageCursor.fingerprint(
                lane,
                keyword,
                clausesText,
                Integer.toString(top),
                Boolean.toString(groupByVersion),
                modelTypes.toString(),
                units.toString());
    }
}

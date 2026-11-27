package org.masymos.neo4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.Page;
import org.masymos.core.query.PageCursor;
import org.masymos.core.query.QueryPlan;
import org.masymos.core.query.SedmlSearchResult;
import org.masymos.core.spi.SedmlQueryRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;

/**
 * SED-ML descriptions from the model index, restricted to {@code :SEDML_MODEL} and carrying the model
 * sources each description references. The clauses are planned by {@link Neo4jModelFulltextQuery}, so
 * the SED-ML fields ({@code OUTPUT_TYPE}, {@code DATA_LABEL}, {@code SIM_KISAO}, {@code SIM_TYPE},
 * {@code MATH}) render exactly as they do on the model queries.
 */
public final class Neo4jSedmlQueryRepository implements SedmlQueryRepository {
    /** An exact-only hit has no index score; this is the documented constant. */
    private static final double EXACT_SCORE = 1.0;

    /** The lane name of the cursor fingerprint; it must differ per lane or one lane pages another. */
    static final String SEDML = "sedml";

    /** The SED-ML order key: the node's uid, or the document's, {@code coalesce}d so it still orders. */
    private static final String SEDML_IDENTITY = "coalesce(node.uid, document.uid)";

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jSedmlQueryRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public List<SedmlSearchResult> querySedml(ModelQueryRequest request) {
        return querySedmlPage(request).hits();
    }

    /**
     * The SED-ML query as a keyset page ({@code OPN-6}), continuing by the same rule the model lane uses:
     * a total order ({@code score DESC, nodeIdentity}, or the identity alone for an exact-only hit), a
     * statement asked for one row beyond {@code top}, and the identity projected under
     * {@code nodeIdentity} so the cursor can name the position.
     */
    @Override
    public Page<SedmlSearchResult> querySedmlPage(ModelQueryRequest request) {
        var plan = Neo4jModelFulltextQuery.plan(request);
        if (plan.isEmpty() || skipsExactOnly(plan, request)) {
            return Page.last(List.of());
        }
        var bound = addBound(plan, request);
        try (var session = driver.session(sessionConfig)) {
            var cypher = plan.hasText()
                    ? sedmlCypher(bound.predicates(), bound.cursor())
                    : sedmlExactCypher(bound.predicates(), bound.cursor());
            var rows = session.executeRead(tx -> tx.run(cypher, bound.parameters()).list(record -> new PagedHits.Hit<>(
                    result(record),
                    record.get("score").asDouble(),
                    record.get("nodeIdentity").asString())));
            return PagedHits.page(rows, request, SEDML);
        }
    }

    /**
     * A built bound is the statement's parameters, the rendered exact predicates the statement embeds, and
     * the decoded cursor the statement shape depends on.
     */
    private record Bound(Map<String, Object> parameters, String predicates, PageCursor cursor) {}

    /**
     * Binds one run of the SED-ML lane: the exact predicates, the text query and floor when there is one,
     * one row beyond {@code top} so the probe row proves whether more follows, and the keyset position
     * when a cursor was supplied.
     */
    private static Bound addBound(QueryPlan plan, ModelQueryRequest request) {
        var cursor = PagedHits.cursorOf(request, SEDML);
        var parameters = new HashMap<String, Object>();
        var rendered = Neo4jResourceQueryRepository.predicates(plan.exactMatches(), parameters);
        if (plan.hasText()) {
            parameters.put("keyword", plan.textQuery());
            parameters.put("minScore", minScore(request));
        }
        parameters.put("limit", request.top() + 1);
        if (cursor != null) {
            parameters.put("cursorScore", cursor.score());
            parameters.put("cursorIdentity", cursor.identity());
        }
        return new Bound(parameters, rendered, cursor);
    }

    /**
     * An exact-only hit always scores the constant {@link #EXACT_SCORE}, so a floor above it can never
     * match; answering in Java saves the round trip, as the resource queries do.
     */
    private static boolean skipsExactOnly(QueryPlan plan, ModelQueryRequest request) {
        return !plan.hasText() && minScore(request) > EXACT_SCORE;
    }

    /** A null floor means "no cut", expressed as {@code 0.0} because the Cypher parameter is required. */
    private static double minScore(ModelQueryRequest request) {
        return request.minScore() == null ? 0.0 : request.minScore();
    }

    private static String andPredicates(String predicates) {
        return predicates.isEmpty() ? "" : "AND " + predicates;
    }

    /**
     * The full-text SED-ML query. The reference list is collected from the description's own
     * {@code HAS_MODELREFERENCE} edges and falls back to the declared source when resolution left
     * {@code resolvedSource} empty, so a {@code urn:miriam:} reference still reports what it declared.
     */
    static String sedmlCypher() {
        return sedmlCypher("", null);
    }

    static String sedmlCypher(String predicates) {
        return sedmlCypher(predicates, null);
    }

    static String sedmlCypher(String predicates, PageCursor cursor) {
        return """
                CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)
                YIELD node, score
                WHERE node:SEDML_MODEL AND score >= $minScore %s
                %s
                """.formatted(andPredicates(predicates), sedmlProjection("score", cursor));
    }

    /** The exact-only SED-ML query: no full-text call, the documented constant score {@code 1.0}. */
    static String sedmlExactCypher(String predicates) {
        return sedmlExactCypher(predicates, null);
    }

    static String sedmlExactCypher(String predicates, PageCursor cursor) {
        return """
                MATCH (node:SEDML_MODEL)
                WHERE %s
                %s
                """.formatted(predicates, sedmlProjection("1.0", cursor));
    }

    /**
     * The projection every SED-ML query shares: the document row, the model sources and the score. A
     * metadata-only {@code :Document:Model:SEDML_MODEL} node is its own document ({@code coalesce}),
     * so it reports the same fields a parsed graph does.
     *
     * <p>The identity is bound in the {@code WITH} that binds {@code score}, and the keyset's {@code WHERE}
     * follows it: that is the one place both are in scope, and a filter there runs before the
     * {@code HAS_MODELREFERENCE} {@code OPTIONAL MATCH}, so the page counts hits, not reference rows.
     */
    private static String sedmlProjection(String scoreExpression, PageCursor cursor) {
        return """
                OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)
                WITH node, coalesce(document, node) AS document, %s AS score, %s AS nodeIdentity
                %s
                OPTIONAL MATCH (node)-[:HAS_MODELREFERENCE]->(reference:SEDML_MODELREFERENCE)
                RETURN coalesce(node.uid, document.uid) AS uid,
                       coalesce(document.fileId, node.fileId) AS fileId,
                       coalesce(node.name, document.fileId) AS modelName,
                       document.sourceUri AS documentUri,
                       coalesce(document.versionId, node.versionId) AS versionId,
                       score,
                       collect(DISTINCT coalesce(reference.resolvedSource, reference.modelSource)) AS referencedModelUris,
                       nodeIdentity
                ORDER BY score DESC, nodeIdentity
                LIMIT $limit
                """.formatted(scoreExpression, SEDML_IDENTITY, keyset(scoreExpression, cursor));
    }

    /**
     * The keyset continuation of {@code score DESC, identity}. A lane whose score is the constant
     * {@code 1.0} continues on the identity alone and must not name {@code $cursorScore}.
     */
    private static String keyset(String scoreExpression, PageCursor cursor) {
        if (cursor == null) {
            return "";
        }
        var predicate = scoreExpression.equals("score")
                ? "score < $cursorScore OR (score = $cursorScore AND " + SEDML_IDENTITY
                        + " > $cursorIdentity)"
                : SEDML_IDENTITY + " > $cursorIdentity";
        return "AND (" + predicate + ")";
    }

    private static SedmlSearchResult result(Record record) {
        return new SedmlSearchResult(
                record.get("uid").asString(),
                record.get("fileId").asString(null),
                record.get("modelName").asString(null),
                uri(record.get("documentUri")),
                record.get("versionId").asString(null),
                record.get("score").asDouble(),
                referencedModelUris(record));
    }

    /** The reference list without the {@code null} an unmatched {@code OPTIONAL MATCH} contributes. */
    private static List<URI> referencedModelUris(Record record) {
        var uris = new ArrayList<URI>();
        for (var value : record.get("referencedModelUris").asList(value -> value)) {
            if (value != null && !value.isNull()) {
                uris.add(URI.create(value.asString()));
            }
        }
        return List.copyOf(uris);
    }

    private static URI uri(Value value) {
        return value == null || value.isNull() ? null : URI.create(value.asString());
    }
}

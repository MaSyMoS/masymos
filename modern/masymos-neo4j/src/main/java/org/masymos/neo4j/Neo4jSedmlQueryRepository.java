package org.masymos.neo4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.masymos.core.query.ModelQueryRequest;
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

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jSedmlQueryRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public List<SedmlSearchResult> querySedml(ModelQueryRequest request) {
        var plan = Neo4jModelFulltextQuery.plan(request);
        if (plan.isEmpty() || skipsExactOnly(plan, request)) {
            return List.of();
        }
        var parameters = new HashMap<String, Object>();
        parameters.put("top", request.top());
        var predicates = Neo4jResourceQueryRepository.predicates(plan.exactMatches(), parameters);
        if (plan.hasText()) {
            parameters.put("keyword", plan.textQuery());
            parameters.put("minScore", minScore(request));
        }
        try (var session = driver.session(sessionConfig)) {
            var cypher = plan.hasText() ? sedmlCypher(predicates) : sedmlExactCypher(predicates);
            return session.executeRead(tx -> tx.run(cypher, parameters).list(Neo4jSedmlQueryRepository::result));
        }
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
        return """
                CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)
                YIELD node, score
                WHERE node:SEDML_MODEL AND score >= $minScore
                %s
                """.formatted(sedmlProjection("score"));
    }

    static String sedmlCypher(String predicates) {
        return """
                CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)
                YIELD node, score
                WHERE node:SEDML_MODEL AND score >= $minScore %s
                %s
                """.formatted(andPredicates(predicates), sedmlProjection("score"));
    }

    /** The exact-only SED-ML query: no full-text call, the documented constant score {@code 1.0}. */
    static String sedmlExactCypher(String predicates) {
        return """
                MATCH (node:SEDML_MODEL)
                WHERE %s
                %s
                """.formatted(predicates, sedmlProjection("1.0"));
    }

    /**
     * The projection every SED-ML query shares: the document row, the model sources and the score. A
     * metadata-only {@code :Document:Model:SEDML_MODEL} node is its own document ({@code coalesce}),
     * so it reports the same fields a parsed graph does.
     */
    private static String sedmlProjection(String scoreExpression) {
        return """
                OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)
                WITH node, coalesce(document, node) AS document, %s AS score
                OPTIONAL MATCH (node)-[:HAS_MODELREFERENCE]->(reference:SEDML_MODELREFERENCE)
                RETURN coalesce(node.uid, document.uid) AS uid,
                       coalesce(document.fileId, node.fileId) AS fileId,
                       coalesce(node.name, document.fileId) AS modelName,
                       document.sourceUri AS documentUri,
                       coalesce(document.versionId, node.versionId) AS versionId,
                       score,
                       collect(DISTINCT coalesce(reference.resolvedSource, reference.modelSource)) AS referencedModelUris
                ORDER BY score DESC
                LIMIT $top
                """.formatted(scoreExpression);
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

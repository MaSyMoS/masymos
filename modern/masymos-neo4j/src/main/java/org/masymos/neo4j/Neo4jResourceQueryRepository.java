package org.masymos.neo4j;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.PersonSearchResult;
import org.masymos.core.query.PersonSummary;
import org.masymos.core.query.PublicationSearchResult;
import org.masymos.core.query.QueryPlan;
import org.masymos.core.spi.ResourceQueryRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;

public final class Neo4jResourceQueryRepository implements ResourceQueryRepository {
    /** An exact-only hit has no index score; this is the documented constant. */
    private static final double EXACT_SCORE = 1.0;

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jResourceQueryRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public List<AnnotationSearchResult> queryAnnotations(ModelQueryRequest request) {
        var plan = Neo4jResourceFulltextQuery.annotations(request);
        if (plan.isEmpty() || skipsExactOnly(plan, request)) {
            return List.of();
        }
        var parameters = new HashMap<String, Object>();
        parameters.put("top", request.top());
        var predicates = predicates(plan.exactMatches(), parameters);
        if (plan.hasText()) {
            parameters.put("keyword", plan.textQuery());
            parameters.put("minScore", minScore(request));
        }
        try (var session = driver.session(sessionConfig)) {
            var cypher = plan.hasText() ? annotationCypher(predicates) : annotationExactCypher(predicates);
            return session.executeRead(tx -> tx.run(cypher, parameters).list(record -> new AnnotationSearchResult(
                    record.get("uri").asString(""),
                    relatedModelUris(record),
                    record.get("score").asDouble()
            )));
        }
    }

    @Override
    public List<PersonSearchResult> queryPersons(ModelQueryRequest request) {
        var plan = Neo4jResourceFulltextQuery.persons(request);
        if (plan.isEmpty() || skipsExactOnly(plan, request)) {
            return List.of();
        }
        var parameters = new HashMap<String, Object>();
        parameters.put("top", request.top());
        var predicates = predicates(plan.exactMatches(), parameters);
        if (plan.hasText()) {
            parameters.put("keyword", plan.textQuery());
            parameters.put("minScore", minScore(request));
        }
        try (var session = driver.session(sessionConfig)) {
            var cypher = plan.hasText() ? personCypher(predicates) : personExactCypher(predicates);
            return session.executeRead(tx -> tx.run(cypher, parameters).list(record -> new PersonSearchResult(
                    person(record),
                    relatedModelUris(record),
                    record.get("score").asDouble()
            )));
        }
    }

    @Override
    public List<PublicationSearchResult> queryPublications(ModelQueryRequest request) {
        var plan = Neo4jResourceFulltextQuery.publications(request);
        if (plan.isEmpty() || skipsExactOnly(plan, request)) {
            return List.of();
        }
        var parameters = new HashMap<String, Object>();
        parameters.put("top", request.top());
        var predicates = predicates(plan.exactMatches(), parameters);
        if (plan.hasText()) {
            parameters.put("keyword", plan.textQuery());
            parameters.put("minScore", minScore(request));
        }
        try (var session = driver.session(sessionConfig)) {
            var cypher = plan.hasText() ? publicationCypher(predicates) : publicationExactCypher(predicates);
            return session.executeRead(tx -> tx.run(cypher, parameters).list(record -> new PublicationSearchResult(
                    record.get("title").asString(""),
                    record.get("journal").asString(""),
                    record.get("affiliation").asString(""),
                    record.get("year").asString(""),
                    authors(record),
                    relatedModelUris(record),
                    record.get("score").asDouble()
            )));
        }
    }

    /**
     * An exact-only hit always scores the constant {@link #EXACT_SCORE}, so a floor above it can never
     * match; answering in Java saves the round trip. A text request keeps the Cypher-side floor.
     */
    private static boolean skipsExactOnly(QueryPlan plan, ModelQueryRequest request) {
        return !plan.hasText() && minScore(request) > EXACT_SCORE;
    }

    /** A null floor means "no cut", expressed as {@code 0.0} because the Cypher parameter is required. */
    private static double minScore(ModelQueryRequest request) {
        return request.minScore() == null ? 0.0 : request.minScore();
    }

    /**
     * Renders the equality predicates of the plan into the parameter map. One value stays a single
     * equality; several values (the resolved annotation URI forms) become a grouped OR. Names are
     * {@code exact<i>} and {@code exact<i>_<j>}.
     */
    static String predicates(List<ExactMatch> exactMatches, Map<String, Object> parameters) {
        var predicates = new StringBuilder();
        for (var index = 0; index < exactMatches.size(); index++) {
            var match = exactMatches.get(index);
            if (match.values().size() == 1) {
                var name = "exact" + index;
                parameters.put(name, match.values().getFirst());
                predicates.append("node.").append(match.property()).append(" = $").append(name);
                continue;
            }
            predicates.append('(');
            for (var valueIndex = 0; valueIndex < match.values().size(); valueIndex++) {
                var name = "exact" + index + "_" + valueIndex;
                parameters.put(name, match.values().get(valueIndex));
                if (valueIndex > 0) {
                    predicates.append(" OR ");
                }
                predicates.append("node.").append(match.property()).append(" = $").append(name);
            }
            predicates.append(')');
        }
        return predicates.toString();
    }

    /**
     * The equality predicates of a full-text query, appended to the label-and-floor {@code WHERE} with AND,
     * or an empty string when the request carries no exact clause.
     */
    private static String andPredicates(String predicates) {
        return predicates.isEmpty() ? "" : "AND " + predicates;
    }

    static String annotationCypher() {
        return """
                CALL db.index.fulltext.queryNodes('annotationFulltextV1', $keyword)
                YIELD node, score
                WHERE node:RESOURCE AND score >= $minScore
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.uri AS uri,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score
                ORDER BY score DESC
                LIMIT $top
                """;
    }

    static String annotationCypher(String predicates) {
        return """
                CALL db.index.fulltext.queryNodes('annotationFulltextV1', $keyword)
                YIELD node, score
                WHERE node:RESOURCE AND score >= $minScore %s
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.uri AS uri,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score
                ORDER BY score DESC
                LIMIT $top
                """.formatted(andPredicates(predicates));
    }

    static String annotationExactCypher(String predicates) {
        return """
                MATCH (node:RESOURCE)
                WHERE %s
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.uri AS uri,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       1.0 AS score
                ORDER BY node.uri
                LIMIT $top
                """.formatted(predicates);
    }

    static String personCypher() {
        return """
                CALL db.index.fulltext.queryNodes('personFulltextV1', $keyword)
                YIELD node, score
                WHERE node:PERSON AND score >= $minScore
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.givenName AS givenName,
                       node.familyName AS familyName,
                       node.email AS email,
                       node.organization AS organization,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score
                ORDER BY score DESC
                LIMIT $top
                """;
    }

    static String personCypher(String predicates) {
        return """
                CALL db.index.fulltext.queryNodes('personFulltextV1', $keyword)
                YIELD node, score
                WHERE node:PERSON AND score >= $minScore %s
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.givenName AS givenName,
                       node.familyName AS familyName,
                       node.email AS email,
                       node.organization AS organization,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score
                ORDER BY score DESC
                LIMIT $top
                """.formatted(andPredicates(predicates));
    }

    static String personExactCypher(String predicates) {
        return """
                MATCH (node:PERSON)
                WHERE %s
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.givenName AS givenName,
                       node.familyName AS familyName,
                       node.email AS email,
                       node.organization AS organization,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       1.0 AS score
                ORDER BY node.familyName, node.givenName
                LIMIT $top
                """.formatted(predicates);
    }

    static String publicationCypher() {
        return """
                CALL db.index.fulltext.queryNodes('publicationFulltextV1', $keyword)
                YIELD node, score
                WHERE node:PUBLICATION AND score >= $minScore
                OPTIONAL MATCH (node)-[:HAS_AUTHOR]->(author:PERSON)
                WITH node, score, collect(DISTINCT author) AS authors
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.title AS title,
                       node.journal AS journal,
                       node.affiliation AS affiliation,
                       node.year AS year,
                       [author IN authors | {
                         givenName: author.givenName,
                         familyName: author.familyName,
                         email: author.email,
                         organization: author.organization
                       }] AS authors,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score
                ORDER BY score DESC
                LIMIT $top
                """;
    }

    static String publicationCypher(String predicates) {
        return """
                CALL db.index.fulltext.queryNodes('publicationFulltextV1', $keyword)
                YIELD node, score
                WHERE node:PUBLICATION AND score >= $minScore %s
                OPTIONAL MATCH (node)-[:HAS_AUTHOR]->(author:PERSON)
                WITH node, score, collect(DISTINCT author) AS authors
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.title AS title,
                       node.journal AS journal,
                       node.affiliation AS affiliation,
                       node.year AS year,
                       [author IN authors | {
                         givenName: author.givenName,
                         familyName: author.familyName,
                         email: author.email,
                         organization: author.organization
                       }] AS authors,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score
                ORDER BY score DESC
                LIMIT $top
                """.formatted(andPredicates(predicates));
    }

    /**
     * Exact-only publication query. The {@code HAS_AUTHOR} collection is kept so the projection fills
     * {@code authors} exactly as the full-text variant does; the constant score is bound before the
     * second {@code OPTIONAL MATCH} so the row survives it.
     */
    static String publicationExactCypher(String predicates) {
        return """
                MATCH (node:PUBLICATION)
                WHERE %s
                OPTIONAL MATCH (node)-[:HAS_AUTHOR]->(author:PERSON)
                WITH node, 1.0 AS score, collect(DISTINCT author) AS authors
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.title AS title,
                       node.journal AS journal,
                       node.affiliation AS affiliation,
                       node.year AS year,
                       [author IN authors | {
                         givenName: author.givenName,
                         familyName: author.familyName,
                         email: author.email,
                         organization: author.organization
                       }] AS authors,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score
                ORDER BY node.pubId
                LIMIT $top
                """.formatted(predicates);
    }

    private static PersonSummary person(Record record) {
        return new PersonSummary(
                record.get("givenName").asString(""),
                record.get("familyName").asString(""),
                record.get("email").asString(""),
                record.get("organization").asString("")
        );
    }

    private static List<PersonSummary> authors(Record record) {
        return record.get("authors").asList(author -> new PersonSummary(
                author.get("givenName").asString(""),
                author.get("familyName").asString(""),
                author.get("email").asString(""),
                author.get("organization").asString("")
        ));
    }

    private static List<URI> relatedModelUris(Record record) {
        return record.get("relatedModelUris").asList(value -> URI.create(value.asString()));
    }
}

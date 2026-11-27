package org.masymos.neo4j;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.Page;
import org.masymos.core.query.PageCursor;
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

    /** The lane names of the cursor fingerprints; they must differ per lane or a person cursor pages annotations. */
    static final String ANNOTATIONS = "annotations";

    static final String PERSONS = "persons";

    static final String PUBLICATIONS = "publications";

    /** The annotation order key: the uri, {@code coalesce}d so a missing one still orders. */
    private static final String ANNOTATION_IDENTITY = "coalesce(node.uri,'')";

    /** The documented person natural key; both halves {@code coalesce}d so a missing one still orders. */
    private static final String PERSON_IDENTITY =
            "coalesce(node.familyName,'') + '|' + coalesce(node.givenName,'')";

    /** The publication key is {@code pubId}; the title only makes the order total for a shared identifier. */
    private static final String PUBLICATION_IDENTITY =
            "coalesce(node.pubId,'') + '|' + coalesce(node.title,'')";

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jResourceQueryRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public List<AnnotationSearchResult> queryAnnotations(ModelQueryRequest request) {
        return queryAnnotationsPage(request).hits();
    }

    /**
     * The annotation query as a keyset page ({@code OPN-6}), continuing by the same rule the model lane
     * uses: a total order ({@code score DESC, nodeIdentity}, or the identity alone for an exact-only hit),
     * a statement asked for one row beyond {@code top}, and the identity projected under {@code nodeIdentity}
     * so the cursor can name the position.
     */
    @Override
    public Page<AnnotationSearchResult> queryAnnotationsPage(ModelQueryRequest request) {
        var plan = Neo4jResourceFulltextQuery.annotations(request);
        if (plan.isEmpty() || skipsExactOnly(plan, request)) {
            return Page.last(List.of());
        }
        var bound = addBound(plan, request, ANNOTATIONS);
        try (var session = driver.session(sessionConfig)) {
            var cypher = plan.hasText()
                    ? annotationCypher(bound.predicates(), bound.cursor())
                    : annotationExactCypher(bound.predicates(), bound.cursor());
            var rows = session.executeRead(tx -> tx.run(cypher, bound.parameters()).list(record -> new PagedHits.Hit<>(
                    new AnnotationSearchResult(
                            record.get("uri").asString(""),
                            relatedModelUris(record),
                            record.get("score").asDouble()),
                    record.get("score").asDouble(),
                    record.get("nodeIdentity").asString())));
            return PagedHits.page(rows, request, ANNOTATIONS);
        }
    }

    @Override
    public List<PersonSearchResult> queryPersons(ModelQueryRequest request) {
        return queryPersonsPage(request).hits();
    }

    /**
     * The person query as a keyset page ({@code OPN-6}). The identity is the documented natural key,
     * {@code familyName|givenName}, both halves {@code coalesce}d so a missing property still orders and
     * cannot drop out of a later page.
     */
    @Override
    public Page<PersonSearchResult> queryPersonsPage(ModelQueryRequest request) {
        var plan = Neo4jResourceFulltextQuery.persons(request);
        if (plan.isEmpty() || skipsExactOnly(plan, request)) {
            return Page.last(List.of());
        }
        var bound = addBound(plan, request, PERSONS);
        try (var session = driver.session(sessionConfig)) {
            var cypher = plan.hasText()
                    ? personCypher(bound.predicates(), bound.cursor())
                    : personExactCypher(bound.predicates(), bound.cursor());
            var rows = session.executeRead(tx -> tx.run(cypher, bound.parameters()).list(record -> new PagedHits.Hit<>(
                    new PersonSearchResult(
                            person(record),
                            relatedModelUris(record),
                            record.get("score").asDouble()),
                    record.get("score").asDouble(),
                    record.get("nodeIdentity").asString())));
            return PagedHits.page(rows, request, PERSONS);
        }
    }

    @Override
    public List<PublicationSearchResult> queryPublications(ModelQueryRequest request) {
        return queryPublicationsPage(request).hits();
    }

    /**
     * The publication query as a keyset page ({@code OPN-6}). The identity is {@code pubId|title}: the
     * identifier is the key and the title only makes the order total for two rows sharing an identifier.
     */
    @Override
    public Page<PublicationSearchResult> queryPublicationsPage(ModelQueryRequest request) {
        var plan = Neo4jResourceFulltextQuery.publications(request);
        if (plan.isEmpty() || skipsExactOnly(plan, request)) {
            return Page.last(List.of());
        }
        var bound = addBound(plan, request, PUBLICATIONS);
        try (var session = driver.session(sessionConfig)) {
            var cypher = plan.hasText()
                    ? publicationCypher(bound.predicates(), bound.cursor())
                    : publicationExactCypher(bound.predicates(), bound.cursor());
            var rows = session.executeRead(tx -> tx.run(cypher, bound.parameters()).list(record -> new PagedHits.Hit<>(
                    new PublicationSearchResult(
                            record.get("title").asString(""),
                            record.get("journal").asString(""),
                            record.get("affiliation").asString(""),
                            record.get("year").asString(""),
                            authors(record),
                            relatedModelUris(record),
                            record.get("score").asDouble()),
                    record.get("score").asDouble(),
                    record.get("nodeIdentity").asString())));
            return PagedHits.page(rows, request, PUBLICATIONS);
        }
    }

    /**
     * A built bound is the statement's parameters, the rendered exact predicates the statement embeds, and
     * the decoded cursor the statement shape depends on.
     */
    private record Bound(Map<String, Object> parameters, String predicates, PageCursor cursor) {}

    /**
     * Binds one run of the annotation, person or publication lane: the exact predicates, the text query and
     * floor when there is one, one row beyond {@code top} so the probe row proves whether more follows, and
     * the keyset position when a cursor was supplied.
     */
    private static Bound addBound(QueryPlan plan, ModelQueryRequest request, String lane) {
        var cursor = PagedHits.cursorOf(request, lane);
        var parameters = new HashMap<String, Object>();
        var rendered = predicates(plan.exactMatches(), parameters);
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
     * Renders one predicate per match into the parameter map, {@code node.<property> IN $exact<i>},
     * joined with AND by the callers. The statement is therefore a function of the clause count, never
     * of the value count: the alternatives of one clause (the MIRIAM resolver's several URI forms)
     * travel as one bound list, so a one-value request and a thousand-value request render
     * byte-identically. That is the {@code AGENTS.md} rule {@code CORP7-11} applied to the model lane;
     * {@code CORP7-13} closes it here.
     */
    static String predicates(List<ExactMatch> exactMatches, Map<String, Object> parameters) {
        var predicates = new StringBuilder();
        for (var index = 0; index < exactMatches.size(); index++) {
            var match = exactMatches.get(index);
            var name = "exact" + index;
            parameters.put(name, match.values());
            if (index > 0) {
                predicates.append(" AND ");
            }
            predicates.append("node.").append(match.property()).append(" IN $").append(name);
        }
        return predicates.toString();
    }

    /**
     * The exact predicates of a full-text query, appended to the label-and-floor {@code WHERE} with AND,
     * or an empty string when the request carries no exact clause.
     */
    private static String andPredicates(String predicates) {
        return predicates.isEmpty() ? "" : "AND " + predicates;
    }

    static String annotationCypher() {
        return annotationCypher("", null);
    }

    static String annotationCypher(String predicates) {
        return annotationCypher(predicates, null);
    }

    static String annotationCypher(String predicates, PageCursor cursor) {
        return """
                CALL db.index.fulltext.queryNodes('annotationFulltextV1', $keyword)
                YIELD node, score
                WHERE node:RESOURCE AND score >= $minScore %s%s
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.uri AS uri,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score,
                       coalesce(node.uri,'') AS nodeIdentity
                ORDER BY score DESC, nodeIdentity
                LIMIT $limit
                """.formatted(andPredicates(predicates), keyset(scoredKeyset(ANNOTATION_IDENTITY), cursor));
    }

    static String annotationExactCypher(String predicates) {
        return annotationExactCypher(predicates, null);
    }

    static String annotationExactCypher(String predicates, PageCursor cursor) {
        return """
                MATCH (node:RESOURCE)
                WHERE %s%s
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.uri AS uri,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       1.0 AS score,
                       coalesce(node.uri,'') AS nodeIdentity
                ORDER BY nodeIdentity
                LIMIT $limit
                """.formatted(predicates, keyset(identityKeyset(ANNOTATION_IDENTITY), cursor));
    }

    static String personCypher() {
        return personCypher("", null);
    }

    static String personCypher(String predicates) {
        return personCypher(predicates, null);
    }

    static String personCypher(String predicates, PageCursor cursor) {
        return """
                CALL db.index.fulltext.queryNodes('personFulltextV1', $keyword)
                YIELD node, score
                WHERE node:PERSON AND score >= $minScore %s%s
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.givenName AS givenName,
                       node.familyName AS familyName,
                       node.email AS email,
                       node.organization AS organization,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       score,
                       %s AS nodeIdentity
                ORDER BY score DESC, nodeIdentity
                LIMIT $limit
                """.formatted(
                andPredicates(predicates), keyset(scoredKeyset(PERSON_IDENTITY), cursor), PERSON_IDENTITY);
    }

    static String personExactCypher(String predicates) {
        return personExactCypher(predicates, null);
    }

    static String personExactCypher(String predicates, PageCursor cursor) {
        return """
                MATCH (node:PERSON)
                WHERE %s%s
                OPTIONAL MATCH (node)-[*1..5]-(document:Document)
                RETURN node.givenName AS givenName,
                       node.familyName AS familyName,
                       node.email AS email,
                       node.organization AS organization,
                       collect(DISTINCT document.sourceUri) AS relatedModelUris,
                       1.0 AS score,
                       %s AS nodeIdentity
                ORDER BY nodeIdentity
                LIMIT $limit
                """.formatted(predicates, keyset(identityKeyset(PERSON_IDENTITY), cursor), PERSON_IDENTITY);
    }

    static String publicationCypher() {
        return publicationCypher("", null);
    }

    static String publicationCypher(String predicates) {
        return publicationCypher(predicates, null);
    }

    static String publicationCypher(String predicates, PageCursor cursor) {
        return """
                CALL db.index.fulltext.queryNodes('publicationFulltextV1', $keyword)
                YIELD node, score
                WHERE node:PUBLICATION AND score >= $minScore %s%s
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
                       score,
                       %s AS nodeIdentity
                ORDER BY score DESC, nodeIdentity
                LIMIT $limit
                """.formatted(
                andPredicates(predicates), keyset(scoredKeyset(PUBLICATION_IDENTITY), cursor),
                PUBLICATION_IDENTITY);
    }

    /**
     * Exact-only publication query. The {@code HAS_AUTHOR} collection is kept so the projection fills
     * {@code authors} exactly as the full-text variant does; the constant score is bound before the
     * second {@code OPTIONAL MATCH} so the row survives it.
     */
    static String publicationExactCypher(String predicates) {
        return publicationExactCypher(predicates, null);
    }

    static String publicationExactCypher(String predicates, PageCursor cursor) {
        return """
                MATCH (node:PUBLICATION)
                WHERE %s%s
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
                       score,
                       %s AS nodeIdentity
                ORDER BY nodeIdentity
                LIMIT $limit
                """.formatted(predicates, keyset(identityKeyset(PUBLICATION_IDENTITY), cursor),
                PUBLICATION_IDENTITY);
    }

    /**
     * The keyset continuation of {@code score DESC, identity}, written as the order itself: everything that
     * sorts before the cursor is skipped and the identity breaks the tie, so no row repeats or vanishes
     * between two pages. The identity expression is {@code coalesce}d exactly as the projection is.
     */
    private static String scoredKeyset(String identity) {
        return "score < $cursorScore OR (score = $cursorScore AND " + identity + " > $cursorIdentity)";
    }

    /** The keyset continuation of the exact-only order, whose score is the constant {@code 1.0}. */
    private static String identityKeyset(String identity) {
        return identity + " > $cursorIdentity";
    }

    /**
     * The keyset appended to a {@code WHERE} clause when a cursor was supplied, and the empty string
     * otherwise: a statement without a cursor must not name one, and a statement's keyset lives in the
     * filtering {@code WHERE}, never after a {@code WITH ... collect(...)}.
     */
    private static String keyset(String predicate, PageCursor cursor) {
        return cursor == null ? "" : "AND (" + predicate + ")";
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

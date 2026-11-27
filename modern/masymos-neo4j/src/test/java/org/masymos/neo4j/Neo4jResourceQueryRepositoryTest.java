package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;
import org.neo4j.driver.Driver;

class Neo4jResourceQueryRepositoryTest {
    private static final Driver UNUSED_DRIVER = null;

    @Test
    void everyFulltextCypherCutsOnTheScoreFloor() {
        assertTrue(Neo4jResourceQueryRepository.annotationCypher().contains("WHERE node:RESOURCE AND score >= $minScore"));
        assertTrue(Neo4jResourceQueryRepository.personCypher().contains("WHERE node:PERSON AND score >= $minScore"));
        assertTrue(Neo4jResourceQueryRepository.publicationCypher().contains("WHERE node:PUBLICATION AND score >= $minScore"));
    }

    @Test
    void zeroArgumentCypherKeepsTheFulltextCallAndTheProjection() {
        var annotation = Neo4jResourceQueryRepository.annotationCypher();

        assertTrue(annotation.contains("CALL db.index.fulltext.queryNodes('annotationFulltextV1', $keyword)"));
        assertTrue(annotation.contains("RETURN node.uri AS uri,"));
        assertTrue(annotation.contains("collect(DISTINCT document.sourceUri) AS relatedModelUris"));
        assertTrue(annotation.contains("ORDER BY score DESC, nodeIdentity"));
        assertTrue(annotation.contains("LIMIT $limit"));
        assertFalse(annotation.contains("MATCH (node:RESOURCE)"));
    }

    @Test
    void annotationCypherRendersEqualityPredicates() {
        var cypher = Neo4jResourceQueryRepository.annotationCypher("node.uriExact = $exact0");

        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('annotationFulltextV1', $keyword)"));
        assertTrue(cypher.contains("WHERE node:RESOURCE AND score >= $minScore AND node.uriExact = $exact0"));
        assertFalse(cypher.contains("MATCH (node:RESOURCE)"));
        assertTrue(Neo4jResourceQueryRepository.personCypher("node.familyNameExact = $exact0")
                .contains("WHERE node:PERSON AND score >= $minScore AND node.familyNameExact = $exact0"));
        assertTrue(
                Neo4jResourceQueryRepository.publicationCypher(
                                "node.yearExact = $exact0 AND node.pubIdExact = $exact1")
                        .contains("WHERE node:PUBLICATION AND score >= $minScore "
                                + "AND node.yearExact = $exact0 AND node.pubIdExact = $exact1")
        );
    }

    @Test
    void annotationExactCypherIsAMatchWithoutTheFulltextCall() {
        var cypher = Neo4jResourceQueryRepository.annotationExactCypher("node.uriExact = $exact0");

        assertTrue(cypher.contains("MATCH (node:RESOURCE)"));
        assertTrue(cypher.contains("WHERE node.uriExact = $exact0"));
        assertTransparentTerminations(cypher, "coalesce(node.uri,'')", "RESOURCE");
        assertTrue(cypher.contains("OPTIONAL MATCH (node)-[*1..5]-(document:Document)"));
        assertTrue(cypher.contains("collect(DISTINCT document.sourceUri) AS relatedModelUris"));
        assertTrue(cypher.contains("LIMIT $limit"));
    }

    @Test
    void personCypherRendersEveryExactPredicateOfAClause() {
        var cypher = Neo4jResourceQueryRepository.personCypher(
                "node.familyNameExact = $exact0 AND node.givenNameExact = $exact1");
        var exact = Neo4jResourceQueryRepository.personExactCypher(
                "node.familyNameExact = $exact0 AND node.givenNameExact = $exact1");

        assertTrue(cypher.contains("AND node.familyNameExact = $exact0 AND node.givenNameExact = $exact1"));
        assertTrue(exact.contains("MATCH (node:PERSON)"));
        assertTrue(exact.contains("WHERE node.familyNameExact = $exact0 AND node.givenNameExact = $exact1"));
        assertTransparentTerminations(
                exact, "coalesce(node.familyName,'') + '|' + coalesce(node.givenName,'')", "PERSON");
        assertTrue(exact.contains("OPTIONAL MATCH (node)-[*1..5]-(document:Document)"));
        assertTrue(exact.contains("collect(DISTINCT document.sourceUri) AS relatedModelUris"));

        var single = Neo4jResourceQueryRepository.personExactCypher("node.familyNameExact = $exact0");
        assertTrue(single.contains("WHERE node.familyNameExact = $exact0"));
    }

    @Test
    void publicationExactCypherKeepsTheAuthorCollection() {
        var cypher = Neo4jResourceQueryRepository.publicationExactCypher("node.yearExact = $exact0");

        assertTrue(cypher.contains("MATCH (node:PUBLICATION)"));
        assertTrue(cypher.contains("WHERE node.yearExact = $exact0"));
        assertTrue(cypher.contains("OPTIONAL MATCH (node)-[:HAS_AUTHOR]->(author:PERSON)"));
        assertTrue(cypher.contains("WITH node, 1.0 AS score, collect(DISTINCT author) AS authors"));
        assertTrue(cypher.contains("[author IN authors | {"));
        assertTrue(cypher.contains("] AS authors,"));
        assertTransparentTerminations(
                cypher, "coalesce(node.pubId,'') + '|' + coalesce(node.title,'')", "PUBLICATION");
        assertTrue(cypher.contains("OPTIONAL MATCH (node)-[*1..5]-(document:Document)"));
        assertTrue(cypher.contains("collect(DISTINCT document.sourceUri) AS relatedModelUris"));
        assertTrue(Neo4jResourceQueryRepository.publicationExactCypher(
                        "node.yearExact = $exact0 AND node.pubIdExact = $exact1")
                .contains("WHERE node.yearExact = $exact0 AND node.pubIdExact = $exact1"));
    }

    @Test
    void predicateRendererPrefixesTheNodeVariableAndBindsTheValues() {
        var parameters = new java.util.HashMap<String, Object>();

        var rendered = Neo4jResourceQueryRepository.predicates(
                List.of(new ExactMatch("uriExact", List.of("urn:miriam:pubmed:1", "https://identifiers.org/pubmed:1"))),
                parameters);

        assertEquals("node.uriExact IN $exact0", rendered);
        assertEquals(
                List.of("urn:miriam:pubmed:1", "https://identifiers.org/pubmed:1"),
                parameters.get("exact0")
        );
    }

    @Test
    void keepsThePredicateBoundedHoweverManyValuesTheRequestCarries() {
        var one = Neo4jResourceQueryRepository.predicates(
                List.of(new ExactMatch("uriExact", List.of("urn:miriam:pubmed:1"))),
                new java.util.HashMap<String, Object>());
        var two = new java.util.HashMap<String, Object>();
        var twoRendered = Neo4jResourceQueryRepository.predicates(
                List.of(new ExactMatch("uriExact",
                        List.of("urn:miriam:pubmed:1", "https://identifiers.org/pubmed:1"))),
                two);
        var many = new java.util.HashMap<String, Object>();
        var manyRendered = Neo4jResourceQueryRepository.predicates(
                List.of(new ExactMatch("uriExact", thousandValues())),
                many);

        // The rule in AGENTS.md: the statement must not grow with the request.
        assertEquals("node.uriExact IN $exact0", one);
        assertEquals(one, twoRendered, "the exact predicate must not render one equality per requested value");
        assertEquals(one, manyRendered, "the exact predicate must not render one equality per requested value");
        assertFalse(manyRendered.contains("$exact0_"), manyRendered);

        assertEquals(2, ((List<?>) two.get("exact0")).size());
        assertEquals(1000, ((List<?>) many.get("exact0")).size());
    }

    @Test
    void resolvedAnnotationUrnYieldsAGroupedOrOverTheResolvedForms() {
        var plan = Neo4jResourceFulltextQuery.annotations(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.RESOURCE_URI, "urn:miriam:pubmed:11429446")),
                10
        ));

        assertEquals(1, plan.exactMatches().size());
        assertEquals(
                List.of("urn:miriam:pubmed:11429446", "https://identifiers.org/pubmed:11429446"),
                plan.exactMatches().getFirst().values()
        );
    }

    @Test
    void exactOnlyRequestAboveTheConstantScoreReturnsEmptyWithoutCypher() {
        var repository = new Neo4jResourceQueryRepository(UNUSED_DRIVER, "neo4j");

        assertEquals(List.of(), repository.queryAnnotations(exactOnly(QueryField.RESOURCE_URI, 1.5)));
        assertEquals(List.of(), repository.queryPersons(exactOnly(QueryField.FAMILYNAME, 1.5)));
        assertEquals(List.of(), repository.queryPublications(exactOnly(QueryField.YEAR, 1.5)));
    }

    /**
     * A request that is exact-only and would otherwise run the exact Cypher. The document/transaction
     * path would fail on the absent driver, so an empty list proves the Java-side floor check ran
     * before any Cypher was executed.
     */
    private static ModelQueryRequest exactOnly(QueryField field, double minScore) {
        return new ModelQueryRequest(
                null,
                List.of(new QueryClause(field, "anything")),
                10,
                minScore
        );
    }

    private static void assertTransparentTerminations(String cypher, String identity, String label) {
        assertTrue(cypher.contains("1.0 AS score"), cypher);
        assertTrue(cypher.contains("ORDER BY nodeIdentity"), cypher);
        assertTrue(cypher.contains(identity + " AS nodeIdentity"), cypher);
        assertTrue(cypher.contains("LIMIT $limit"), cypher);
        assertFalse(cypher.contains("queryNodes"), cypher);
        assertFalse(cypher.contains("$keyword"), cypher);
        assertFalse(cypher.contains("$minScore"), cypher);
        assertTrue(cypher.contains("MATCH (node:" + label + ")"), cypher);
    }

    private static List<String> thousandValues() {
        var values = new java.util.ArrayList<String>();
        for (var index = 0; index < 1000; index++) {
            values.add("urn:miriam:pubmed:" + index);
        }
        return values;
    }
}

package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.PageCursor;

/**
 * Shape guards for the paged annotation, person and publication statements ({@code OPN-6}), mirroring
 * {@link ModelQueryPagingCypherTest}. They read the generated Cypher; the sweep itself is proven against a
 * real server by {@code Neo4jContainersIT}.
 *
 * <p>What must hold per lane: every statement orders by a total order whose tiebreak is the identity it
 * projects as {@code nodeIdentity}, the keyset continues exactly that order, the page bound is the caller's
 * {@code limit} rather than the old {@code $top}, a statement without a cursor never names one, and an
 * exact-only statement never references {@code $cursorScore} because its score is the constant {@code 1.0}.
 */
class ResourceQueryPagingCypherTest {
    private static final PageCursor CURSOR = new PageCursor(1.5, "node-7", "finger");

    private static final String ANNOTATION_IDENTITY = "coalesce(node.uri,'')";
    private static final String PERSON_IDENTITY =
            "coalesce(node.familyName,'') + '|' + coalesce(node.givenName,'')";
    private static final String PUBLICATION_IDENTITY =
            "coalesce(node.pubId,'') + '|' + coalesce(node.title,'')";

    @Test
    void theAnnotationFullTextStatementContinuesOnScoreThenUri() {
        var cypher = Neo4jResourceQueryRepository.annotationCypher("", CURSOR);

        assertScoredShape(cypher, ANNOTATION_IDENTITY);
    }

    @Test
    void theAnnotationExactStatementContinuesOnTheUriAlone() {
        var cypher = Neo4jResourceQueryRepository.annotationExactCypher("node.uriExact IN $exact0", CURSOR);

        assertExactShape(cypher, ANNOTATION_IDENTITY);
    }

    @Test
    void thePersonFullTextStatementContinuesOnScoreThenTheNaturalKey() {
        var cypher = Neo4jResourceQueryRepository.personCypher("", CURSOR);

        assertScoredShape(cypher, PERSON_IDENTITY);
    }

    @Test
    void thePersonExactStatementContinuesOnTheNaturalKeyAlone() {
        var cypher = Neo4jResourceQueryRepository.personExactCypher("node.familyNameExact IN $exact0", CURSOR);

        assertExactShape(cypher, PERSON_IDENTITY);
    }

    @Test
    void thePublicationFullTextStatementContinuesOnScoreThenPubIdAndTitle() {
        var cypher = Neo4jResourceQueryRepository.publicationCypher("", CURSOR);

        assertScoredShape(cypher, PUBLICATION_IDENTITY);
    }

    @Test
    void thePublicationExactStatementContinuesOnPubIdAndTitleAlone() {
        var cypher = Neo4jResourceQueryRepository.publicationExactCypher("node.pubIdExact IN $exact0", CURSOR);

        assertExactShape(cypher, PUBLICATION_IDENTITY);
    }

    @Test
    void everyLaneProjectsTheIdentityItOrdersBy() {
        assertEquals(
                "coalesce(node.uri,'') AS nodeIdentity",
                Neo4jResourceQueryRepository.annotationCypher("", CURSOR).lines()
                        .filter(line -> line.contains("AS nodeIdentity"))
                        .map(String::trim)
                        .findFirst()
                        .orElseThrow());
        assertEquals(
                "coalesce(node.familyName,'') + '|' + coalesce(node.givenName,'') AS nodeIdentity",
                Neo4jResourceQueryRepository.personCypher("", CURSOR).lines()
                        .filter(line -> line.contains("AS nodeIdentity"))
                        .map(String::trim)
                        .findFirst()
                        .orElseThrow());
        assertEquals(
                "coalesce(node.pubId,'') + '|' + coalesce(node.title,'') AS nodeIdentity",
                Neo4jResourceQueryRepository.publicationCypher("", CURSOR).lines()
                        .filter(line -> line.contains("AS nodeIdentity"))
                        .map(String::trim)
                        .findFirst()
                        .orElseThrow());
        // The title makes the order total, so the identity goes after the authors list it must not disturb.
        var publication = Neo4jResourceQueryRepository.publicationCypher("", CURSOR);
        assertTrue(publication.indexOf("] AS authors,") < publication.indexOf("AS nodeIdentity"), publication);
    }

    @Test
    void theKeysetSitsInTheFilteringWhereBeforeTheTraversal() {
        var publication = Neo4jResourceQueryRepository.publicationCypher("", CURSOR);

        assertTrue(
                publication.indexOf("$cursorIdentity") < publication.indexOf("WITH node, score, collect"),
                "a keyset after a collected row would page the documents, not the hits: " + publication);
    }

    @Test
    void theUnpagedOverloadsKeepTheirStatementShape() {
        var unpaged = List.of(
                Neo4jResourceQueryRepository.annotationCypher(),
                Neo4jResourceQueryRepository.annotationCypher("node.uriExact IN $exact0"),
                Neo4jResourceQueryRepository.annotationExactCypher("node.uriExact IN $exact0"),
                Neo4jResourceQueryRepository.personCypher(),
                Neo4jResourceQueryRepository.personCypher("node.familyNameExact IN $exact0"),
                Neo4jResourceQueryRepository.personExactCypher("node.familyNameExact IN $exact0"),
                Neo4jResourceQueryRepository.publicationCypher(),
                Neo4jResourceQueryRepository.publicationCypher("node.pubIdExact IN $exact0"),
                Neo4jResourceQueryRepository.publicationExactCypher("node.pubIdExact IN $exact0"));

        for (var cypher : unpaged) {
            assertFalse(cypher.contains("$cursorScore"), "no keyset without a cursor: " + cypher);
            assertFalse(cypher.contains("cursor"), "no cursor named without one: " + cypher);
            assertTrue(cypher.contains("LIMIT $limit"), cypher);
            assertFalse(cypher.contains("$top"), "the page bound is the caller's limit: " + cypher);
        }
    }

    private static void assertScoredShape(String cypher, String identity) {
        assertTrue(cypher.contains("ORDER BY score DESC, nodeIdentity"), cypher);
        assertTrue(cypher.contains("LIMIT $limit"), cypher);
        assertFalse(cypher.contains("$top"), "the page bound is the caller's limit: " + cypher);
        assertTrue(
                cypher.contains("score < $cursorScore OR (score = $cursorScore AND " + identity + " > $cursorIdentity)"),
                cypher);
        assertTrue(cypher.contains(identity + " AS nodeIdentity"), cypher);
    }

    private static void assertExactShape(String cypher, String identity) {
        assertTrue(cypher.contains("ORDER BY nodeIdentity"), cypher);
        assertTrue(cypher.contains("AND (" + identity + " > $cursorIdentity)"), cypher);
        assertFalse(cypher.contains("$cursorScore"), "an exact-only hit has a constant score: " + cypher);
        assertTrue(cypher.contains("LIMIT $limit"), cypher);
        assertTrue(cypher.contains(identity + " AS nodeIdentity"), cypher);
        assertTrue(cypher.contains("1.0 AS score"), cypher);
    }
}

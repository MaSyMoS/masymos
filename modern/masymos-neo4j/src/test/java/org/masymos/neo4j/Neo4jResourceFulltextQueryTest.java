package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;

class Neo4jResourceFulltextQueryTest {
    @Test
    void buildsAnnotationQuery() {
        var plan = Neo4jResourceFulltextQuery.annotations(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.RESOURCE_TEXT, "glycolysis")),
                10
        ));

        assertEquals("resourceText:(glycolysis)", plan.textQuery());
        assertTrue(plan.exactMatches().isEmpty());
    }

    @Test
    void buildsPersonQuery() {
        var plan = Neo4jResourceFulltextQuery.persons(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.EMAIL, "doe@example.org")),
                10
        ));

        assertEquals("email:(doe@example.org)", plan.textQuery());
        assertTrue(plan.exactMatches().isEmpty());
    }

    @Test
    void buildsPublicationQuery() {
        var plan = Neo4jResourceFulltextQuery.publications(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.TITLE, "cell cycle")),
                10
        ));

        assertEquals("title:(cell cycle)", plan.textQuery());
        assertTrue(plan.exactMatches().isEmpty());
    }

    @Test
    void annotationUriBecomesAnExactMatchThatPreservesCase() {
        var plan = Neo4jResourceFulltextQuery.annotations(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.RESOURCE_URI, "Http://Example.org/A")),
                10
        ));

        assertEquals("", plan.textQuery());
        assertEquals(List.of(new ExactMatch("uriExact", List.of("Http://Example.org/A"))), plan.exactMatches());
    }

    @Test
    void annotationUriClauseUsesTheSameExactPropertyAsResourceUri() {
        var plan = Neo4jResourceFulltextQuery.annotations(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.URI, "http://example.org/A")),
                10
        ));

        assertEquals(List.of(new ExactMatch("uriExact", List.of("http://example.org/A"))), plan.exactMatches());
    }

    @Test
    void miriamUrnExpandsToTheIdentifiersOrgFormWithoutSeparatorFragments() {
        var plan = Neo4jResourceFulltextQuery.annotations(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.RESOURCE_URI, "urn:miriam:pubmed:11429446")),
                10
        ));

        assertEquals(
                List.of(new ExactMatch(
                        "uriExact",
                        List.of("urn:miriam:pubmed:11429446", "https://identifiers.org/pubmed:11429446")
                )),
                plan.exactMatches()
        );
    }

    @Test
    void annotationTextFieldStaysInTheTextQuery() {
        var plan = Neo4jResourceFulltextQuery.annotations(new ModelQueryRequest(
                "glycolysis",
                List.of(new QueryClause(QueryField.RESOURCE_TEXT, "glycolysis")),
                10
        ));

        assertEquals("glycolysis resourceText:(glycolysis)", plan.textQuery());
        assertTrue(plan.exactMatches().isEmpty());
    }

    @Test
    void personNamesAndPublicationIdentityBecomeLowercasedExactMatches() {
        var persons = Neo4jResourceFulltextQuery.persons(new ModelQueryRequest(
                null,
                List.of(
                        new QueryClause(QueryField.FAMILYNAME, "  Doe "),
                        new QueryClause(QueryField.GIVENNAME, "Jane")
                ),
                10
        ));
        var publications = Neo4jResourceFulltextQuery.publications(new ModelQueryRequest(
                null,
                List.of(
                        new QueryClause(QueryField.YEAR, "2014"),
                        new QueryClause(QueryField.ID, "PMID-123")
                ),
                10
        ));

        assertEquals("", persons.textQuery());
        assertEquals(
                List.of(
                        new ExactMatch("familyNameExact", List.of("doe")),
                        new ExactMatch("givenNameExact", List.of("jane"))
                ),
                persons.exactMatches()
        );
        assertEquals("", publications.textQuery());
        assertEquals(
                List.of(
                        new ExactMatch("yearExact", List.of("2014")),
                        new ExactMatch("pubIdExact", List.of("pmid-123"))
                ),
                publications.exactMatches()
        );
    }

    @Test
    void exactPropertyOrderFollowsTheEmittedPredicates() {
        var plan = Neo4jResourceFulltextQuery.persons(new ModelQueryRequest(
                null,
                List.of(
                        new QueryClause(QueryField.FAMILYNAME, "Doe"),
                        new QueryClause(QueryField.GIVENNAME, "Jane")
                ),
                10
        ));

        // The predicate order follows the clause order of the request.
        assertEquals(
                List.of(
                        new ExactMatch("familyNameExact", List.of("doe")),
                        new ExactMatch("givenNameExact", List.of("jane"))
                ),
                plan.exactMatches()
        );
    }

    @Test
    void personAndPublicationTextFieldsStayInTheTextQuery() {
        var persons = Neo4jResourceFulltextQuery.persons(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.ORGANIZATION, "EMBL-EBI")),
                10
        ));
        var publications = Neo4jResourceFulltextQuery.publications(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.TITLE, "cell cycle")),
                10
        ));

        assertEquals("organization:(EMBL\\-EBI)", persons.textQuery());
        assertTrue(persons.exactMatches().isEmpty());
        assertEquals("title:(cell cycle)", publications.textQuery());
        assertTrue(publications.exactMatches().isEmpty());
    }

    @Test
    void duplicateExactValuesCollapseIntoOnePredicate() {
        var plan = Neo4jResourceFulltextQuery.persons(new ModelQueryRequest(
                null,
                List.of(
                        new QueryClause(QueryField.FAMILYNAME, "Doe"),
                        new QueryClause(QueryField.FAMILYNAME, "DOE")
                ),
                10
        ));

        assertEquals(List.of(new ExactMatch("familyNameExact", List.of("doe"))), plan.exactMatches());
    }
}

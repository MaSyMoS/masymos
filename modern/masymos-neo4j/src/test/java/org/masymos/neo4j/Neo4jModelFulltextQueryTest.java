package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.IndexText;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;
import org.masymos.core.query.QueryPlan;

class Neo4jModelFulltextQueryTest {
    @Test
    void buildsWeightedLegacySbmlFieldQuery() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(
                        new QueryClause(QueryField.NAME, "novak"),
                        new QueryClause(QueryField.SPECIES, "ATP"),
                        new QueryClause(QueryField.CREATOR, "Smith")
                ),
                10
        ));

        assertEquals("name:(novak)^3 species:(ATP)^2 creator:(Smith)^1", query);
    }

    @Test
    void combinesGeneralKeywordAndStructuredClauses() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                "glycolysis",
                List.of(new QueryClause(QueryField.REACTION, "hexokinase")),
                10
        ));

        assertEquals("glycolysis reaction:(hexokinase)^2", query);
    }

    @Test
    void escapesLuceneSpecialCharactersAndAddsCaseFragments() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.SPECIES, "ATP:ADP (cytosol)")),
                10
        ));

        // QRY-5: the separators yield case-split fragments behind the exact form.
        assertEquals("species:((ATP\\:ADP \\(cytosol\\) OR atp OR adp))^2", query);
    }

    @Test
    void treatsKeywordFieldAsUnqualifiedSearchTerm() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.KEYWORD, "mitosis")),
                10
        ));

        assertEquals("mitosis", query);
    }

    @Test
    void buildsLegacySedmlAndAnnotationFieldQueries() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(
                        new QueryClause(QueryField.MODEL_SOURCE, "BIOMD0000000001.xml"),
                        new QueryClause(QueryField.SIM_KISAO, "KISAO:0000019"),
                        new QueryClause(QueryField.RESOURCE_URI, "identifiers.org/go/GO:0008150")
                ),
                10
        ));

        // QRY-5: additive expansion keeps every typed form and adds its fragments, each exactly once.
        assertEquals(
                "modelSource:((BIOMD0000000001.xml OR biomd OR 0000000001 OR xml))^1 "
                        + "simKisao:((KISAO\\:0000019 OR kisao OR 0000019))^1 "
                        + "resourceUri:((identifiers.org\\/go\\/GO\\:0008150 OR identifiers OR org OR go "
                        + "OR 0008150))^4",
                query
        );
    }

    @Test
    void shortSeparatorValueWithoutUsableFragmentsKeepsSingleTerm() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.MODEL_SOURCE, "a.b")),
                10
        ));

        assertEquals("modelSource:(a.b)^1", query);
    }

    @Test
    void groupsMiriamUrnWithIdentifiersOrgCandidateForm() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.RESOURCE_URI, "urn:miriam:pubmed:11429446")),
                10
        ));

        // QRY-5: the URN keeps its identifiers.org form and contributes the separator-split
        // fragments the indexer now writes, each term exactly once.
        assertEquals(
                "resourceUri:((urn\\:miriam\\:pubmed\\:11429446 OR urn OR miriam OR pubmed OR 11429446 "
                        + "OR https\\:\\/\\/identifiers.org\\/pubmed\\:11429446))^4",
                query
        );
    }

    @Test
    void keepsPlainKeywordClauseAsSingleTerm() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.RESOURCE_URI, "pubmed")),
                10
        ));

        assertEquals("resourceUri:(pubmed)^4", query);
    }

    @Test
    void mapsSchemaVersionAcrossEveryVersionProperty() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.SCHEMA_VERSION, "2.0")),
                10
        ));

        // "2.0" is below the five-character precondition, so no fragments are added.
        assertEquals(
                "(sbmlLevel:(2.0) OR sbmlVersion:(2.0) OR cellmlVersion:(2.0) OR sedmlLevel:(2.0) "
                        + "OR sedmlVersion:(2.0))^2",
                query
        );
    }

    @Test
    void groupsSeparatorSplitFragmentsWithOriginalTerm() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.SPECIES, "species_1")),
                10
        ));

        assertEquals("species:((species_1 OR species))^2", query);
    }

    @Test
    void keepsIdentifierFormsAndAddsSeparatorFragments() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.RESOURCE_URI, "urn:miriam:biomodels.db:BIOMD0000001")),
                10
        ));

        assertEquals(
                "resourceUri:((urn\\:miriam\\:biomodels.db\\:BIOMD0000001 OR urn OR miriam OR biomodels "
                        + "OR db OR biomd OR 0000001 OR "
                        + "https\\:\\/\\/identifiers.org\\/biomodels.db\\:BIOMD0000001))^4",
                query
        );
    }

    @Test
    void clampsOversizedClauseValueBelowLegacyTermCeiling() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.RESOURCE_TEXT, "c".repeat(40000))),
                10
        ));

        assertTrue(query.length() < IndexText.LEGACY_TERM_BYTE_LIMIT, "query length " + query.length());
    }

    @Test
    void mapsSingleVersionFieldWithoutGrouping() {
        var query = Neo4jModelFulltextQuery.from(new ModelQueryRequest(
                null,
                List.of(
                        new QueryClause(QueryField.SBML_LEVEL, "3"),
                        new QueryClause(QueryField.CELLML_VERSION, "2.0")
                ),
                10
        ));

        assertEquals("sbmlLevel:(3)^2 cellmlVersion:(2.0)^2", query);
    }

    @Test
    void splitsIdAndUriClausesIntoLowercasedExactMatches() {
        var plan = Neo4jModelFulltextQuery.plan(new ModelQueryRequest(
                null,
                List.of(
                        new QueryClause(QueryField.ID, "  BIOMD0000000001 "),
                        new QueryClause(QueryField.URI, "HTTPS://Example.ORG/Models/BIOMD0000000001")
                ),
                10
        ));

        assertFalse(plan.hasText());
        assertEquals(
                List.of(
                        new ExactMatch("modelIdExact", List.of("biomd0000000001")),
                        new ExactMatch("sourceUriExact", List.of("https://example.org/models/biomd0000000001"))
                ),
                plan.exactMatches()
        );
    }

    @Test
    void keepsTextFieldsInTheLucenePartAndExactFieldsOutOfIt() {
        var plan = Neo4jModelFulltextQuery.plan(new ModelQueryRequest(
                "glycolysis",
                List.of(
                        new QueryClause(QueryField.NAME, "novak"),
                        new QueryClause(QueryField.TITLE, "insulin"),
                        new QueryClause(QueryField.ID, "BIOMD0000000001")
                ),
                10
        ));

        assertTrue(plan.hasText());
        assertEquals("glycolysis name:(novak)^3 title:(insulin)^2", plan.textQuery());
        assertEquals(List.of(new ExactMatch("modelIdExact", List.of("biomd0000000001"))), plan.exactMatches());
    }

    @Test
    void yieldsOnlyTheExactPartForARequestWithoutTextFields() {
        var plan = Neo4jModelFulltextQuery.plan(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.URI, "https://example.org/models/BIOMD0000000001")),
                10
        ));

        assertFalse(plan.isEmpty());
        assertFalse(plan.hasText());
        assertEquals("", plan.textQuery());
        assertEquals(
                List.of(new ExactMatch("sourceUriExact", List.of("https://example.org/models/biomd0000000001"))),
                plan.exactMatches()
        );
    }

    @Test
    void resolvesMiriamUrnIntoAnExactOrWithoutSeparatorFragments() {
        var plan = Neo4jModelFulltextQuery.plan(new ModelQueryRequest(
                null,
                List.of(new QueryClause(QueryField.URI, "urn:miriam:pubmed:11429446")),
                10
        ));

        // QRY-8: the resolver form is a second equality, never a separator fragment.
        assertEquals(
                List.of(new ExactMatch("sourceUriExact", List.of(
                        "urn:miriam:pubmed:11429446",
                        "https://identifiers.org/pubmed:11429446"
                ))),
                plan.exactMatches()
        );
        assertEquals("", plan.textQuery());
        assertEquals(List.of(), IndexText.termFragments("urn:miriam:pubmed:11429446").stream()
                .filter(fragment -> plan.exactMatches().getFirst().values().contains(fragment))
                .toList());
    }

    @Test
    void emptyRequestPlansNothing() {
        var plan = Neo4jModelFulltextQuery.plan(new ModelQueryRequest(null, null, 10));

        assertEquals(new QueryPlan("", List.of()), plan);
        assertTrue(plan.isEmpty());
    }
}

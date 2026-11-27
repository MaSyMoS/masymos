package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelType;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;
import org.masymos.core.query.QueryPlan;

class Neo4jModelRepositoryTest {
    @Test
    void modelQueryCypherReturnsDocumentUidForExtractedGraphs() {
        var cypher = Neo4jModelRepository.queryModelsCypher();

        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)"));
        assertTrue(cypher.contains("WHERE node:Model"));
        assertTrue(cypher.contains("OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)"));
        assertTrue(cypher.contains("RETURN coalesce(node.uid, document.uid) AS uid"));
    }

    @Test
    void insertCypherMintsLegacyUidAndCarriesVersionId() {
        var cypher = Neo4jModelRepository.insertCypher(ModelType.SBML);

        assertTrue(cypher.contains("MERGE (id:GlobalUniqueId)"));
        assertTrue(cypher.contains("ON CREATE SET id.count = 1"));
        assertTrue(cypher.contains("ON MATCH SET id.count = id.count + 1"));
        assertTrue(cypher.contains("legacyUid: legacyUid"));
        assertTrue(cypher.contains("SET d.versionId = $versionId"));
        assertTrue(cypher.contains("RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid"));
    }

    @Test
    void findDocumentCypherReadsTheCapturedContentAndMetadata() {
        var cypher = Neo4jModelRepository.findDocumentCypher();

        assertTrue(cypher.contains("d.xmlDoc AS xmlDoc"));
        assertTrue(cypher.contains("d.xmlDocEncoding AS xmlDocEncoding"));
        assertTrue(cypher.contains("d.xmlDocBytes AS xmlDocBytes"));
        assertTrue(cypher.contains("d.xmlDocTruncated AS xmlDocTruncated"));
        assertTrue(cypher.contains("d.meta AS meta"));
        assertTrue(cypher.contains("WHERE d.uid = $uid OR ($legacyUid IS NOT NULL AND d.legacyUid = $legacyUid)"));
    }

    @Test
    void insertTakesTheFileIdLockBeforeCheckingUniqueness() {
        assertTrue(Neo4jModelRepository.fileIdLockCypher().contains("MERGE (lock:FileIdLock {fileId: $fileId})"));
    }

    @Test
    void insertCypherStoresTheDocumentMetadata() {
        var cypher = Neo4jModelRepository.insertCypher(ModelType.SBML);

        assertTrue(cypher.contains("SET d.meta = $meta"));
    }

    @Test
    void findByUidCypherAcceptsTheNumericLegacyUid() {
        var cypher = Neo4jModelRepository.findByUidCypher();

        assertTrue(cypher.contains("WHERE d.uid = $uid OR ($legacyUid IS NOT NULL AND d.legacyUid = $legacyUid)"));
    }

    @Test
    void findLatestVersionCypherUsesTheSuccessorTopologyNotTheVersionLabel() {
        var cypher = Neo4jModelRepository.findLatestVersionCypher();

        assertTrue(cypher.contains("WHERE NOT (d)-[:HAS_SUCCESSOR]->()"));
        assertTrue(cypher.contains("ORDER BY coalesce(d.legacyUid, 0) DESC"));
        assertTrue(cypher.contains("LIMIT 1"));
        assertFalse(cypher.contains("ORDER BY d.versionId"));
    }

    @Test
    void findVersionsCypherListsEveryVersionOfTheFileOldestFirst() {
        var cypher = Neo4jModelRepository.findVersionsCypher();

        assertTrue(cypher.contains("MATCH (d:Document {fileId: $fileId})"));
        assertTrue(cypher.contains("RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid"));
        assertTrue(cypher.contains("ORDER BY coalesce(d.legacyUid, 0) ASC"));
        assertFalse(cypher.contains("LIMIT"));
        assertFalse(cypher.contains("HAS_SUCCESSOR"));
    }

    @Test
    void findVersionCypherFiltersOnBothPropertiesAndTakesTheNewestMatch() {
        var cypher = Neo4jModelRepository.findVersionCypher();

        assertTrue(cypher.contains("MATCH (d:Document {fileId: $fileId, versionId: $versionId})"));
        assertTrue(cypher.contains("RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid"));
        assertTrue(cypher.contains("ORDER BY coalesce(d.legacyUid, 0) DESC"));
        assertTrue(cypher.contains("LIMIT 1"));
    }

    @Test
    void chainLinkCypherLinksEveryCurrentTailOfTheFile() {        var cypher = ModelVersionChainCypher.linkPredecessorsCypher();

        assertTrue(cypher.contains("OPTIONAL MATCH (predecessor:Document {fileId: $fileId})"));
        assertTrue(cypher.contains("NOT (predecessor)-[:HAS_SUCCESSOR]->()"));
        assertTrue(cypher.contains("CREATE (predecessor)-[:HAS_SUCCESSOR]->(document)"));
        assertTrue(cypher.contains("CREATE (document)-[:HAS_PREDECESSOR]->(predecessor)"));
    }

    @Test
    void mixedModelQueryCypherAndsTheExactPredicatesOntoTheFulltextCall() {
        var cypher = Neo4jModelRepository.queryModelsCypher(
                List.of(new ExactMatch("sourceUriExact", List.of("https://example.org/models/BIOMD1"))));

        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)"));
        assertTrue(cypher.contains("WHERE node:Model AND node.sourceUriExact IN $exact0"));
        assertTrue(cypher.contains("OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)"));
        assertTrue(cypher.contains("ORDER BY score DESC, elementId(node)"));
        assertTrue(cypher.contains("LIMIT $limit"));
    }

    @Test
    void mixedModelQueryCypherCarriesTheValuesOfOneExactPredicateAsOneList() {
        var cypher = Neo4jModelRepository.queryModelsCypher(List.of(new ExactMatch(
                "sourceUriExact",
                List.of("urn:miriam:pubmed:11429446", "https://identifiers.org/pubmed:11429446")
        )));

        assertTrue(cypher.contains("WHERE node:Model AND node.sourceUriExact IN $exact0"));
        assertFalse(cypher.contains(" OR node."), cypher);
        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)"));
    }

    @Test
    void exactOnlyModelQueryCypherMatchesTheSameProjectionAtTheConstantScore() {
        var cypher = Neo4jModelRepository.queryModelsExactCypher(
                List.of(new ExactMatch("sourceUriExact", List.of("https://example.org/models/BIOMD1"))));

        assertTrue(cypher.contains("MATCH (node:Model)"));
        assertTrue(cypher.contains("WHERE node.sourceUriExact IN $exact0"));
        assertFalse(cypher.contains("queryNodes"));
        assertTrue(cypher.contains("OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)"));
        assertTrue(cypher.contains("RETURN coalesce(node.uid, document.uid) AS uid"));
        assertTrue(cypher.contains("1.0 AS score"));
        assertTrue(cypher.contains("ORDER BY elementId(node)"));
        assertTrue(cypher.contains("LIMIT $limit"));
        assertFalse(cypher.contains("ORDER BY node.uid"));
    }

    @Test
    void insertCypherCarriesTheLowercasedSourceUriCopy() {
        assertTrue(Neo4jModelRepository.insertCypher(ModelType.SBML).contains("SET d.sourceUriExact = $sourceUriExact"));
    }

    @Test
    void groupedModelQueryCypherAggregatesTheVersionsBeforeTheLimit() {
        var cypher = Neo4jModelRepository.groupedModelsCypher(List.of());

        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)"));
        assertTrue(cypher.contains("YIELD node, score"));
        assertTrue(cypher.contains("WHERE node:Model"));
        assertTrue(cypher.contains("OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)"));
        assertTrue(cypher.contains("collect({"));
        assertTrue(cypher.contains("reduce(best = versions[0]"));
        assertTrue(cypher.contains("ORDER BY score DESC, fileId"));
        assertTrue(cypher.contains("LIMIT $limit"));
        assertTrue(cypher.contains("] AS versions"));
        assertFalse(cypher.contains("RETURN coalesce(node.uid, document.uid) AS uid"));
    }

    @Test
    void groupedModelQueryCypherAndsTheExactPredicatesOntoTheFulltextCall() {
        var cypher = Neo4jModelRepository.groupedModelsCypher(List.of(new ExactMatch(
                "sourceUriExact",
                List.of("urn:miriam:pubmed:11429446", "https://identifiers.org/pubmed:11429446")
        )));

        assertTrue(cypher.contains("WHERE node:Model AND node.sourceUriExact IN $exact0"));
        assertTrue(cypher.contains("collect({"));
    }

    @Test
    void groupedExactOnlyModelQueryCypherAggregatesAtTheConstantScore() {
        var cypher = Neo4jModelRepository.groupedModelsExactCypher(
                List.of(new ExactMatch("sourceUriExact", List.of("https://example.org/models/BIOMD1"))));

        assertTrue(cypher.contains("MATCH (node:Model)"));
        assertTrue(cypher.contains("WHERE node.sourceUriExact IN $exact0"));
        assertTrue(cypher.contains("score: 1.0"));
        assertFalse(cypher.contains("queryNodes"));
        assertTrue(cypher.contains("collect({"));
        assertTrue(cypher.contains("reduce(best = versions[0]"));
        assertTrue(cypher.contains("ORDER BY fileId"));
        assertTrue(cypher.contains("LIMIT $limit"));
        assertFalse(cypher.contains("RETURN coalesce(node.uid, document.uid) AS uid"));
    }

    @Test
    void flatModelQueryCypherKeepsItsRowProjection() {
        var cypher = Neo4jModelRepository.queryModelsCypher(List.of());

        assertTrue(cypher.contains("RETURN coalesce(node.uid, document.uid) AS uid"));
        assertFalse(cypher.contains("collect("));
    }

    @Test
    void flatModelQueryCypherSelectsTheDocumentFacts() {
        for (var cypher : List.of(
                Neo4jModelRepository.queryModelsCypher(),
                Neo4jModelRepository.queryModelsExactCypher(List.of()))) {
            assertTrue(cypher.contains("coalesce(document.versionId, node.versionId) AS versionId"), cypher);
            assertTrue(cypher.contains("document.xmlDoc IS NOT NULL AS documentStored"), cypher);
            assertTrue(cypher.contains("document.xmlDocBytes AS documentBytes"), cypher);
            assertTrue(cypher.contains("document.xmlDocTruncated AS documentTruncated"), cypher);
            assertTrue(cypher.contains("WITH node, coalesce(document, node) AS document"), cypher);
        }
    }

    @Test
    void groupedModelQueryCypherCollectsAndReturnsTheRepresentativeDocumentFacts() {
        for (var cypher : List.of(
                Neo4jModelRepository.groupedModelsCypher(List.of()),
                Neo4jModelRepository.groupedModelsExactCypher(List.of()))) {
            assertTrue(cypher.contains("versionId: coalesce(document.versionId, node.versionId)"), cypher);
            assertTrue(cypher.contains("documentStored: document.xmlDoc IS NOT NULL"), cypher);
            assertTrue(cypher.contains("documentBytes: document.xmlDocBytes"), cypher);
            assertTrue(cypher.contains("documentTruncated: document.xmlDocTruncated"), cypher);
            assertTrue(cypher.contains("best.versionId AS versionId"), cypher);
            assertTrue(cypher.contains("best.documentStored AS documentStored"), cypher);
            assertTrue(cypher.contains("best.documentBytes AS documentBytes"), cypher);
            assertTrue(cypher.contains("best.documentTruncated AS documentTruncated"), cypher);
            assertTrue(cypher.contains("] AS versions"), cypher);
        }
    }

    @Test
    void aKeywordOnlyRequestMatchesKeyword() {
        var request = new ModelQueryRequest("cancer", List.of(), 10);

        assertEquals(List.of(QueryField.KEYWORD),
                Neo4jModelRepository.matchedFields(plan(request), request));
    }

    @Test
    void aClauseOnlyRequestMatchesTheClauseFieldsInRequestOrder() {
        var request = new ModelQueryRequest(null, List.of(
                new QueryClause(QueryField.NAME, "tumour"),
                new QueryClause(QueryField.YEAR, "2014"),
                new QueryClause(QueryField.NAME, "cancer")), 10);

        assertEquals(List.of(QueryField.NAME, QueryField.YEAR),
                Neo4jModelRepository.matchedFields(plan(request), request));
    }

    @Test
    void aMixedRequestAppendsKeywordAfterTheClauseFields() {
        var request = new ModelQueryRequest("tumour", List.of(
                new QueryClause(QueryField.NAME, "cancer"),
                new QueryClause(QueryField.AUTHOR, "Hucka")), 10);

        assertEquals(List.of(QueryField.NAME, QueryField.AUTHOR, QueryField.KEYWORD),
                Neo4jModelRepository.matchedFields(plan(request), request));
    }

    @Test
    void aBlankKeywordDoesNotAddTheKeywordField() {
        var request = new ModelQueryRequest("   ", List.of(new QueryClause(QueryField.NAME, "cancer")), 10);

        assertEquals(List.of(QueryField.NAME), Neo4jModelRepository.matchedFields(plan(request), request));
    }

    @Test
    void theIndexSourceNamesTheFulltextIndexOnlyForATextQuery() {
        assertEquals("modelFulltextV6", Neo4jModelRepository.indexSource(true));
        assertEquals("exact", Neo4jModelRepository.indexSource(false));
    }

    @Test
    void insertCypherStampsTheKindLabelOfTheInsertedReference() {
        assertTrue(Neo4jModelRepository.insertCypher(ModelType.SBML).contains(":Document:Model:SBML_MODEL"));
        assertTrue(Neo4jModelRepository.insertCypher(ModelType.CELLML).contains(":Document:Model:CELLML_MODEL"));
        assertTrue(Neo4jModelRepository.insertCypher(ModelType.SEDML).contains(":SEDML_MODEL"));
        assertTrue(Neo4jModelRepository.insertCypher(ModelType.OWL).contains(":OWL_MODEL"));
    }

    @Test
    void aP2mInsertKeepsThePlainModelLabel() {
        var cypher = Neo4jModelRepository.insertCypher(ModelType.P2M);

        assertTrue(cypher.contains("CREATE (d:Document:Model {"));
        assertFalse(cypher.contains("_MODEL:"));
        assertFalse(cypher.contains("_MODEL "));
        // The document fact survives; only the filter signal (the label) is absent.
        assertTrue(cypher.contains("modelType: $modelType"));
    }

    @Test
    void theFulltextBuilderRendersOneKindAsALabelPredicate() {
        var cypher = Neo4jModelRepository.queryModelsCypher(List.of(), List.of(ModelType.SBML));

        assertTrue(cypher.contains("WHERE node:Model AND node:SBML_MODEL"));
        assertTrue(cypher.contains("CALL db.index.fulltext.queryNodes('modelFulltextV6', $keyword)"));
        assertTrue(cypher.contains("ORDER BY score DESC, elementId(node)"));
        assertTrue(cypher.contains("LIMIT $limit"));
    }

    @Test
    void theFulltextBuilderRendersSeveralKindsAsAGroupedOrInDeclarationOrder() {
        var cypher = Neo4jModelRepository.queryModelsCypher(
                List.of(), List.of(ModelType.CELLML, ModelType.SBML));

        assertTrue(cypher.contains("WHERE node:Model AND (node:SBML_MODEL OR node:CELLML_MODEL)"));
        assertTrue(Neo4jModelRepository.queryModelsCypher(List.of(), List.of(ModelType.CELLML))
                .contains("WHERE node:Model AND node:CELLML_MODEL"));
    }

    @Test
    void theUnfilteredFulltextBuilderIsByteIdenticalToTheOneWithoutAKindFilter() {
        assertEquals(Neo4jModelRepository.queryModelsCypher(List.of()),
                Neo4jModelRepository.queryModelsCypher(List.of(), List.of()));
        assertTrue(Neo4jModelRepository.queryModelsCypher(List.of()).contains("WHERE node:Model\n"));
    }

    @Test
    void keepsTheExactPredicateBoundedHoweverManyValuesTheRequestCarries() {
        var one = Neo4jModelRepository.queryModelsCypher(
                List.of(new ExactMatch("sourceUriExact", List.of("https://example.org/models/BIOMD1"))));
        var many = Neo4jModelRepository.queryModelsCypher(
                List.of(new ExactMatch("sourceUriExact", thousandValues())));

        // The rule in AGENTS.md: neither the statement nor the plan may grow with the request.
        assertEquals(one, many, "the exact predicate must not render one equality per requested value");
        assertFalse(many.contains("$exact0_"), many);
    }

    @Test
    void bindsOneListPerExactMatchInRequestOrder() {
        var parameters = new HashMap<String, Object>();
        Neo4jModelRepository.putExactParameters(parameters, List.of(
                new ExactMatch("sourceUriExact",
                        List.of("urn:miriam:pubmed:1", "https://identifiers.org/pubmed:1")),
                new ExactMatch("modelIdExact", List.of("biomd1"))));

        assertEquals(List.of("urn:miriam:pubmed:1", "https://identifiers.org/pubmed:1"), parameters.get("exact0"));
        assertEquals(List.of("biomd1"), parameters.get("exact1"));
    }

    private static List<String> thousandValues() {
        var values = new ArrayList<String>();
        for (var index = 0; index < 1000; index++) {
            values.add("https://example.org/models/BIOMD" + index);
        }
        return values;
    }

    @Test
    void theKindPredicateKeepsTheExactPredicatesAndedAfterIt() {
        var cypher = Neo4jModelRepository.queryModelsCypher(
                List.of(new ExactMatch("sourceUriExact", List.of("https://example.org/models/BIOMD1"))),
                List.of(ModelType.SBML));

        assertTrue(cypher.contains("WHERE node:Model AND node:SBML_MODEL AND node.sourceUriExact IN $exact0"));
    }

    @Test
    void theGroupedFulltextBuilderIsByteIdenticalWithoutAKindFilter() {
        assertEquals(Neo4jModelRepository.groupedModelsCypher(List.of()),
                Neo4jModelRepository.groupedModelsCypher(List.of(), List.of()));
        assertTrue(Neo4jModelRepository.groupedModelsCypher(List.of(), List.of(ModelType.SBML))
                .contains("WHERE node:Model AND node:SBML_MODEL"));
        assertTrue(Neo4jModelRepository.groupedModelsCypher(List.of(), List.of())
                .contains("WHERE node:Model\n"));
    }

    @Test
    void theExactOnlyBuildersRenderTheSameKindPredicate() {
        assertTrue(Neo4jModelRepository.queryModelsExactCypher(
                        List.of(new ExactMatch("sourceUriExact", List.of("https://example.org/models/BIOMD1"))),
                        List.of(ModelType.CELLML))
                .contains("WHERE node.sourceUriExact IN $exact0 AND node:CELLML_MODEL"));
        assertTrue(Neo4jModelRepository.groupedModelsExactCypher(
                        List.of(), List.of(ModelType.SBML))
                .contains("WHERE node:Model AND node:SBML_MODEL"));
        assertEquals(Neo4jModelRepository.queryModelsExactCypher(List.of()),
                Neo4jModelRepository.queryModelsExactCypher(List.of(), List.of()));
        assertEquals(Neo4jModelRepository.groupedModelsExactCypher(List.of()),
                Neo4jModelRepository.groupedModelsExactCypher(List.of(), List.of()));
    }

    private static QueryPlan plan(ModelQueryRequest request) {
        return Neo4jModelFulltextQuery.plan(request);
    }
}

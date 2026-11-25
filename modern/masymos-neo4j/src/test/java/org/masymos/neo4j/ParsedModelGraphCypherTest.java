package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ExtractedGraphNode;
import org.masymos.core.extract.ExtractedGraphRelationship;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ParsedAnnotation;
import org.masymos.core.extract.ParsedAnnotationResource;
import org.masymos.core.extract.ParsedEntityRelationship;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.ParsedModelEntity;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.ParsedOntology;
import org.masymos.core.extract.ParsedOntologyGraphMapper;
import org.masymos.core.extract.ParsedOntologyRelationship;
import org.masymos.core.extract.ParsedOntologyTerm;
import org.masymos.core.extract.ParsedPerson;
import org.masymos.core.extract.ParsedPublication;
import org.masymos.core.extract.ParsedReactionParticipant;
import org.masymos.core.extract.SourceDocument;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class ParsedModelGraphCypherTest {
    @Test
    void preparesFixedSbmlGraphWrite() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of("sbml.level", "3", "sbml.version", "2")
        ));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertTrue(write.document().cypher().contains("MERGE (id:GlobalUniqueId)"));
        assertTrue(write.document().cypher().contains("ON CREATE SET id.count = 1"));
        assertTrue(write.document().cypher().contains("WITH id.count AS legacyUid"));
        assertTrue(write.document().cypher().contains(
                "CREATE (n0:Document {uid: $documentUid, legacyUid: legacyUid})"));
        assertTrue(allCypher(write).contains("CREATE (n:Model:SBML_MODEL)"));
        assertTrue(allCypher(write).contains("CREATE (start)-[relationship:`HAS_MODEL`]->(end)"));

        var documentUid = (String) write.document().parameters().get("documentUid");
        assertNotNull(documentUid);
        var documentProperties = assertStringMap(write.document().parameters().get("documentProperties"));
        assertEquals("example.xml", documentProperties.get("fileId"));
        assertEquals("SBML", documentProperties.get("modelType"));
        assertEquals("3", documentProperties.get("sbml.level"));
        assertEquals(documentUid, documentProperties.get("ownerUid"));

        var modelProperties = nodeProperties(write, "model");
        assertEquals("m1", modelProperties.get("modelId"));
        assertEquals("Example model", modelProperties.get("name"));
        assertEquals(documentUid, modelProperties.get("ownerUid"));

        var hasModel = relationshipRows(write, "HAS_MODEL").getFirst();
        assertEquals("document", hasModel.startKey());
        assertEquals("model", hasModel.endKey());
        assertTrue(hasModel.properties().isEmpty());
    }

    @Test
    void tagsEveryNodeOfADocumentWithTheOwningDocumentUidSoDeleteCannotCrossDocuments() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of(),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.COMPARTMENT,
                                "cell",
                                "Cell",
                                Map.of("id", "cell", "name", "Cell")),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SPECIES,
                                "s1",
                                "Substrate",
                                Map.of("id", "s1", "name", "Substrate", "compartment", "cell"))),
                List.of()));

        var write = ParsedModelGraphCypher.prepare(graph);
        var documentUid = (String) write.document().parameters().get("documentUid");

        assertNotNull(documentUid);
        var rows = nodeRows(write);
        assertFalse(rows.isEmpty());
        rows.forEach(row -> assertEquals(
                documentUid, assertStringMap(row.get("properties")).get("ownerUid"), row.get("nodeKey").toString()));
        assertEquals(
                documentUid,
                assertStringMap(write.document().parameters().get("documentProperties")).get("ownerUid"),
                "the document owns itself, exactly as it does today");
    }

    @Test
    void preparesExpandedSbmlEntityGraphWrite() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of("sbml.level", "3", "sbml.version", "2"),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.COMPARTMENT,
                                "cell",
                                "Cell",
                                Map.of("id", "cell", "name", "Cell")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SPECIES,
                                "s1",
                                "Substrate",
                                Map.of("id", "s1", "name", "Substrate", "compartment", "cell")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.REACTION,
                                "r1",
                                "conversion",
                                Map.of("id", "r1", "name", "conversion")
                        )
                ),
                List.of(new ParsedReactionParticipant("r1", "s1", ParsedReactionParticipant.Role.REACTANT))
        ));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertTrue(allCypher(write).contains(":SBML_COMPARTMENT"));
        assertTrue(allCypher(write).contains(":SBML_SPECIES"));
        assertTrue(allCypher(write).contains(":SBML_REACTION"));
        assertTrue(allCypher(write).contains(":`HAS_SPECIES`"));
        assertTrue(allCypher(write).contains(":`IS_LOCATED_IN`"));
        assertTrue(allCypher(write).contains(":`HAS_REACTANT`"));
    }

    @Test
    void preparesPublicationGraphWrite() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedPublication(
                        "pubmed:123",
                        "A model publication",
                        "Journal of Models",
                        "",
                        "",
                        "2024",
                        List.of(new ParsedPerson("Jane", "Doe", "", ""))
                ))
        ));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertTrue(allCypher(write).contains(":PUBLICATION"));
        assertTrue(allCypher(write).contains(":PERSON"));
        assertTrue(allCypher(write).contains(":`HAS_PUBLICATION`"));
        assertTrue(allCypher(write).contains(":`HAS_AUTHOR`"));
    }

    @Test
    void preparesCellmlEntityGraphWrite() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.cellml", URI.create("https://example.org/example.cellml"), ModelType.CELLML),
                "m1",
                "Example CellML model",
                Map.of(),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.COMPONENT,
                                "environment",
                                "environment",
                                Map.of("id", "environment", "name", "environment")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.VARIABLE,
                                "environment/time",
                                "time",
                                Map.of("id", "environment/time", "name", "time", "component", "environment")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.VARIABLE,
                                "environment/clock",
                                "clock",
                                Map.of("id", "environment/clock", "name", "clock", "component", "environment")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.CELLML_REACTION,
                                "environment/reaction-0",
                                "environment/reaction-0",
                                Map.of("id", "environment/reaction-0", "name", "environment/reaction-0", "component", "environment", "reversible", "true")
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.VARIABLE + ":environment/time",
                        "IS_CONNECTED_TO",
                        ParsedModelEntity.EntityType.VARIABLE + ":environment/clock",
                        Map.of()
                ), new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.COMPONENT + ":environment",
                        "HAS_REACTION",
                        ParsedModelEntity.EntityType.CELLML_REACTION + ":environment/reaction-0",
                        Map.of()
                ), new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.CELLML_REACTION + ":environment/reaction-0",
                        "HAS_REACTANT",
                        ParsedModelEntity.EntityType.VARIABLE + ":environment/time",
                        Map.of("role", "reactant")
                ), new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.CELLML_REACTION + ":environment/reaction-0",
                        "HAS_DELTA_VAR",
                        ParsedModelEntity.EntityType.VARIABLE + ":environment/clock",
                        Map.of("deltaVariable", "clock")
                ))
        ));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertTrue(allCypher(write).contains(":CELLML_MODEL"));
        assertTrue(allCypher(write).contains(":CELLML_COMPONENT"));
        assertTrue(allCypher(write).contains(":CELLML_VARIABLE"));
        assertTrue(allCypher(write).contains(":CELLML_REACTION"));
        assertTrue(allCypher(write).contains(":`HAS_COMPONENT`"));
        assertTrue(allCypher(write).contains(":`HAS_VARIABLE`"));
        assertTrue(allCypher(write).contains(":`HAS_REACTION`"));
        assertTrue(allCypher(write).contains(":`IS_CONNECTED_TO`"));
        assertTrue(allCypher(write).contains(":`HAS_REACTANT`"));
        assertTrue(allCypher(write).contains(":`HAS_DELTA_VAR`"));
    }

    /**
     * CML-2/CML-1: a CellML document with an {@code <import>} must be writable at all. The whitelist
     * is the guard against a mapper typo, so it is duplicated on purpose - but it was never extended
     * when imports became entities, and every such document made {@code prepare} throw
     * {@code unsupported labels [CELLML_IMPORT]}. Nothing in the routine build noticed, because no
     * container test indexed an importing model until the imported-unit filter test did.
     */
    @Test
    void preparesCellmlImportGraphWrite() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.cellml", URI.create("https://example.org/example.cellml"), ModelType.CELLML),
                "m1",
                "Example CellML model",
                Map.of(),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.CELLML_IMPORT,
                                "sodium.cellml",
                                "sodium.cellml",
                                Map.of("id", "sodium.cellml", "name", "sodium.cellml", "href", "sodium.cellml", "resolved", "true")),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.CELLML_IMPORTED_COMPONENT,
                                "sodium.cellml#sodium_channel",
                                "sodium_channel",
                                Map.of(
                                        "id", "sodium.cellml#sodium_channel",
                                        "name", "sodium_channel",
                                        "componentRef", "sodium_channel",
                                        "importId", "sodium.cellml",
                                        "resolved", "true"))),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.CELLML_IMPORT + ":sodium.cellml",
                        "HAS_IMPORTED_COMPONENT",
                        ParsedModelEntity.EntityType.CELLML_IMPORTED_COMPONENT + ":sodium.cellml#sodium_channel",
                        Map.of()))));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertTrue(allCypher(write).contains(":CELLML_IMPORT)"), allCypher(write));
        assertTrue(allCypher(write).contains(":CELLML_IMPORTED_COMPONENT)"), allCypher(write));
        assertTrue(
                allCypher(write).contains("relationship:`HAS_IMPORTED_COMPONENT`]->"),
                "the edge, not the node label, is what the imported component needs: " + allCypher(write));
        assertTrue(allCypher(write).contains("relationship:`HAS_IMPORT`]->"), allCypher(write));
    }

    /**
     * FBC-2 (decision 52): the same pin for the gene-product vocabulary. {@code SUPPORTED_LABELS} and
     * {@code SUPPORTED_RELATIONSHIPS} are hand-kept whitelists, and a label or edge missing from them
     * fails at {@code prepare} for the whole document, not for the element - which is exactly how
     * every importing CellML document failed once. Both edges are checked, because the model owns the
     * gene product through one and the reaction references it through the other.
     */
    @Test
    void preparesSbmlGeneProductGraphWrite() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example SBML model",
                Map.of("sbml.level", "3", "sbml.version", "1"),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.REACTION,
                                "R1",
                                "R1",
                                Map.of("id", "R1", "name", "R1")),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SBML_GENE_PRODUCT,
                                "G1",
                                "g1",
                                Map.of("id", "G1", "label", "g1"))),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.REACTION + ":R1",
                        "ASSOCIATED_WITH",
                        ParsedModelEntity.EntityType.SBML_GENE_PRODUCT + ":G1",
                        Map.of()))));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertTrue(allCypher(write).contains(":SBML_GENE_PRODUCT)"), allCypher(write));
        assertTrue(allCypher(write).contains("relationship:`HAS_GENE_PRODUCT`]->"), allCypher(write));
        assertTrue(allCypher(write).contains("relationship:`ASSOCIATED_WITH`]->"), allCypher(write));
    }

    @Test
    void preparesSedmlEntityGraphWrite() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.sedml", URI.create("https://example.org/example.sedml"), ModelType.SEDML),
                "example.sedml",
                "example.sedml",
                Map.of(),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_MODELREFERENCE,
                                "model1",
                                "",
                                Map.of("id", "model1", "name", "", "modelSource", "model.xml", "modelChanged", "false")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_VARIABLE,
                                "var1",
                                "",
                                Map.of("id", "var1", "name", "", "target", "/sbml:sbml/sbml:model", "reference", "task1")
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.SEDML_VARIABLE + ":var1",
                        "CALCULATES_MODEL",
                        ParsedModelEntity.EntityType.SEDML_MODELREFERENCE + ":model1",
                        Map.of()
                ))
        ));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertTrue(allCypher(write).contains(":SEDML_MODEL"));
        assertTrue(allCypher(write).contains(":SEDML_MODELREFERENCE"));
        assertTrue(allCypher(write).contains(":SEDML_VARIABLE"));
        assertTrue(allCypher(write).contains(":`HAS_MODELREFERENCE`"));
        assertTrue(allCypher(write).contains(":`CALCULATES_MODEL`"));
    }

    @Test
    void acceptsTheKisaoResourceRelationshipOfASedmlSimulation() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.sedml", URI.create("https://example.org/example.sedml"), ModelType.SEDML),
                "example.sedml",
                "example.sedml",
                Map.of(),
                List.of(new ParsedModelEntity(
                        ParsedModelEntity.EntityType.SEDML_SIMULATION,
                        "sim1",
                        "",
                        Map.of("id", "sim1", "name", "", "simType", "uniformTimeCourse", "simKisao", "KISAO:0000019")
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));

        var write = ParsedModelGraphCypher.prepare(graph);

        // ANNO-1a: without HAS_KISAO in the whitelist prepare() throws instead of returning.
        assertTrue(allCypher(write).contains(":`HAS_KISAO`"));
        assertTrue(allCypher(write).contains(":RESOURCE"));
        var resourceProperties = nodeRows(write).stream()
                .map(row -> assertStringMap(row.get("properties")))
                .filter(properties -> properties.containsKey("uri"))
                .toList();
        assertEquals(
                List.of(Map.of(
                        "uri", "urn:miriam:biomodels.kisao:KISAO_0000019",
                        "uriExact", "urn:miriam:biomodels.kisao:KISAO_0000019")),
                resourceProperties);
    }

    @Test
    void preparesOwlOntologyGraphWrite() {
        var graph = new ParsedOntologyGraphMapper().toGraph(new ParsedOntology(
                new ModelReference("sbo.owl", URI.create("https://example.org/sbo.owl"), ModelType.OWL),
                "SBOOntology",
                "SBOOntology",
                Map.of(),
                List.of(
                        new ParsedOntologyTerm("owl:SBOOntology", Map.of("id", "owl:SBOOntology", "name", "SBOOntology")),
                        new ParsedOntologyTerm("SBO_0000001", Map.of("id", "SBO_0000001", "name", "entity")),
                        new ParsedOntologyTerm("SBO_0000002", Map.of("id", "SBO_0000002", "name", "child"))
                ),
                List.of(
                        new ParsedOntologyRelationship("SBO_0000001", "isA", "owl:SBOOntology", Map.of()),
                        new ParsedOntologyRelationship(
                                "SBO_0000002", "isA", "SBO_0000001", Map.of("derivedFrom", "equivalentClass"))
                )
        ));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertTrue(allCypher(write).contains(":OWL_MODEL"));
        assertTrue(allCypher(write).contains(":SBOOntology"));
        assertTrue(allCypher(write).contains(":`HAS_ONTOLOGY_TERM`"));
        assertTrue(allCypher(write).contains("relationship:`isA`"));
        assertEquals(
                1,
                relationshipRows(write, "isA").stream()
                        .map(BatchedGraphWrite.Row::properties)
                        .filter(properties -> "equivalentClass".equals(properties.get("derivedFrom")))
                        .count(),
                "OWL-5: a definition-derived edge carries its provenance into the write instead of"
                        + " looking like a stated one");
    }

    @Test
    void rejectsUnsupportedNodeShape() {
        var graph = new ExtractedModelGraph(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                List.of(
                        new ExtractedGraphNode("document", Set.of("Document"), Map.of("fileId", "example.xml")),
                        new ExtractedGraphNode("model", Set.of("Model"), Map.of("modelId", "m1"))
                ),
                List.of(new ExtractedGraphRelationship("document", "HAS_MODEL", "model", Map.of()))
        );

        assertThrows(IllegalArgumentException.class, () -> ParsedModelGraphCypher.prepare(graph));
    }

    /**
     * DB-001, second take: a qualifier that is a Cypher keyword is written as a quoted type instead of
     * being refused. Refusing it was measured wrong on 2026-11-23 - {@code bqbiol:is} is the commonest
     * SBML qualifier, and the refusal made 26 of the 66 curated corpus documents unwritable - so the
     * writer quotes every type and a keyword is a type like any other.
     */
    @Test
    void aQualifierThatIsACypherKeywordIsWrittenAsAQuotedType() {
        for (var keyword : List.of("MATCH", "DELETE", "MERGE", "is")) {
            var write = ParsedModelGraphCypher.prepare(graphWithAnnotationQualifier(keyword));

            assertTrue(
                    allCypher(write).contains("[relationship:`" + keyword + "`]"),
                    keyword + " must be written as a quoted relationship type: " + allCypher(write));
        }
    }

    /**
     * The text a write emits: every type is backtick-quoted, so no type can be read as a clause. This
     * supersedes the unquoted text that `T-025` pinned byte-for-byte - that pin existed to make a
     * quoting change deliberate, and this is that change, taken for the regression recorded above.
     */
    @Test
    void quotesTheRelationshipTypeInEveryStatement() {
        var write = ParsedModelGraphCypher.prepare(graphWithAnnotationQualifier("isDescribedBy"));

        var statements = statements(write);
        assertTrue(statements.contains("UNWIND $rows AS row\n"
                + "MATCH (start) WHERE elementId(start) = row.start\n"
                + "MATCH (end) WHERE elementId(end) = row.end\n"
                + "CREATE (start)-[relationship:`isDescribedBy`]->(end)\n"
                + "SET relationship += row.properties\n"), allCypher(write));
        assertTrue(statements.contains("UNWIND $rows AS row\n"
                + "MATCH (start) WHERE elementId(start) = row.start\n"
                + "MATCH (end) WHERE elementId(end) = row.end\n"
                + "CREATE (start)-[relationship:`HAS_ANNOTATION`]->(end)\n"
                + "SET relationship += row.properties\n"), allCypher(write));
    }

    /** One SBML model carrying one annotation whose single resource has {@code qualifier}. */
    private ExtractedModelGraph graphWithAnnotationQualifier(String qualifier) {
        return new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ParsedAnnotation(
                        "model",
                        Map.of("id", "ann1"),
                        List.of(new ParsedAnnotationResource("https://identifiers.org/go/GO:0008150", qualifier)),
                        List.of(),
                        List.of()))));
    }

    @SuppressWarnings("unchecked")
    @Test
    void writesTheCapturedSourceDocumentAndMetadataOnTheDocumentNode() {
        var document = SourceDocument.from("<sbml>Grüße</sbml>".getBytes(StandardCharsets.UTF_8), 4096);
        var graph = new ParsedModelGraphMapper()
                .toGraph(new ParsedModel(
                        new ModelReference(
                                "example.xml",
                                URI.create("https://example.org/example.xml"),
                                ModelType.SBML,
                                null,
                                Map.of("submitter", "jj")),
                        "m1",
                        "Example model",
                        Map.of()))
                .withSourceDocument(document);

        var write = ParsedModelGraphCypher.prepare(graph);
        var documentProperties = assertStringMap(write.document().parameters().get("documentProperties"));

        assertEquals("<sbml>Grüße</sbml>", documentProperties.get("xmlDoc"));
        assertEquals("UTF-8", documentProperties.get("xmlDocEncoding"));
        assertEquals(document.originalBytes(), documentProperties.get("xmlDocBytes"));
        assertEquals(Boolean.FALSE, documentProperties.get("xmlDocTruncated"));
        assertEquals("{\"submitter\":\"jj\"}", documentProperties.get("meta"));
    }

    @Test
    void omitsTheStoredDocumentPropertiesWhenNoDocumentWasCaptured() {
        var graph = new ParsedModelGraphMapper()
                .toGraph(new ParsedModel(
                        new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                        "m1",
                        "Example model",
                        Map.of()));

        var write = ParsedModelGraphCypher.prepare(graph);
        var documentProperties = assertStringMap(write.document().parameters().get("documentProperties"));

        assertFalse(documentProperties.containsKey("xmlDoc"));
        assertFalse(documentProperties.containsKey("xmlDocEncoding"));
        assertFalse(documentProperties.containsKey("xmlDocBytes"));
        assertFalse(documentProperties.containsKey("xmlDocTruncated"));
        assertFalse(documentProperties.containsKey("meta"));
    }

    @Test
    void doesNotOwnTheAnnotationNodesAnotherDocumentMayShare() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ParsedAnnotation(
                        "model",
                        Map.of("id", "ann1"),
                        List.of(new ParsedAnnotationResource("https://identifiers.org/go/GO:0008150", "isDescribedBy")),
                        List.of(new ParsedPerson("Jane", "Doe", "jj@example.org", "Uni")),
                        List.of())),
                List.of(new ParsedPublication(
                        "pub-1", "Title", "Journal", null, null, "2014",
                        List.of(new ParsedPerson("John", "Roe", "jr@example.org", "Uni"))))));

        var write = ParsedModelGraphCypher.prepare(graph);
        var documentUid = (String) write.document().parameters().get("documentUid");
        var nodes = nodeRows(write);

        assertTrue(nodes.stream().allMatch(row -> {
                    var properties = assertStringMap(row.get("properties"));
                    return properties.containsKey("ownerUid") && properties.get("ownerUid").equals(documentUid)
                            || properties.containsKey("uri")
                            || properties.containsKey("givenName")
                            || properties.containsKey("pubId");
                }),
                "only annotation nodes another document could share may be unowned: " + nodes);
        assertTrue(nodes.stream().anyMatch(row -> assertStringMap(row.get("properties")).containsKey("uri")));
        assertTrue(nodes.stream().anyMatch(row -> assertStringMap(row.get("properties")).containsKey("givenName")));
        assertTrue(nodes.stream().anyMatch(row -> assertStringMap(row.get("properties")).containsKey("pubId")));
        assertTrue(nodes.stream()
                        .map(row -> assertStringMap(row.get("properties")))
                        .filter(properties -> properties.containsKey("uri"))
                        .noneMatch(properties -> properties.containsKey("ownerUid")),
                "a RESOURCE must not be owned, so a delete purges it only when nothing references it");
        assertTrue(nodes.stream()
                        .map(row -> assertStringMap(row.get("properties")))
                        .filter(properties -> properties.containsKey("givenName"))
                        .noneMatch(properties -> properties.containsKey("ownerUid")),
                "a PERSON must not be owned");
        assertTrue(nodes.stream()
                        .map(row -> assertStringMap(row.get("properties")))
                        .filter(properties -> properties.containsKey("pubId"))
                        .noneMatch(properties -> properties.containsKey("ownerUid")),
                "a PUBLICATION must not be owned");
    }

    /**
     * QRY-8: an exact clause is an equality on a normalized copy, so every identity property the
     * planners match on must be stamped here — the one write path — and nowhere else.
     */
    @Test
    void stampsTheNormalizedExactCopyOfEveryIdentityProperty() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("HTTPS://Example.ORG/Models/BIOMD1"), ModelType.SBML),
                "BIOMD0000000001",
                "Example model",
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ParsedAnnotation(
                        "model",
                        Map.of("id", "ann1"),
                        List.of(new ParsedAnnotationResource("https://identifiers.org/GO:0008150", "isDescribedBy")),
                        List.of(),
                        List.of())),
                List.of(new ParsedPublication(
                        "PubMed:123", "Title", "Journal", "", "", "2024", List.of())),
                List.of()));

        var write = ParsedModelGraphCypher.prepare(graph);
        var model = nodeProperties(write, "model");
        var document = assertStringMap(write.document().parameters().get("documentProperties"));
        var resource = nodeProperties(write, "resource:https://identifiers.org/GO:0008150");
        var publication = nodeProperties(write, "publication:PubMed:123");

        assertEquals("https://example.org/models/biomd1", model.get("sourceUriExact"));
        assertEquals("biomd0000000001", model.get("modelIdExact"));
        assertEquals("https://example.org/models/biomd1", document.get("sourceUriExact"));
        assertFalse(document.containsKey("modelIdExact"), "a Document has no modelId to copy");
        assertEquals("https://identifiers.org/GO:0008150", resource.get("uriExact"), "the annotation URI is case-sensitive");
        assertEquals("pubmed:123", publication.get("pubIdExact"));
        assertEquals("2024", publication.get("yearExact"));
    }

    @Test
    void stampsTheLowercasedExactCopiesOfThePersonNames() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedPublication(
                        "pub-1", "Title", "Journal", "", "", "2014",
                        List.of(new ParsedPerson("Jane", "Doe", "jj@example.org", "Uni"))))));

        var write = ParsedModelGraphCypher.prepare(graph);
        var person = nodeProperties(write, "person:Jane:Doe:jj@example.org");

        assertEquals("doe", person.get("familyNameExact"));
        assertEquals("jane", person.get("givenNameExact"));
    }

    @Test
    void writesNoExactCopyForABlankOrAbsentIdentityProperty() {
        var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                " ",
                "Example model",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedPublication(
                        "pub-1", "Title", "Journal", "", "", "", List.of()))));

        var write = ParsedModelGraphCypher.prepare(graph);

        assertFalse(nodeProperties(write, "model").containsKey("modelIdExact"));
        var publication = nodeProperties(write, "publication:pub-1");
        assertFalse(publication.containsKey("yearExact"), "a blank year is no year: an empty exact value would never match");
        assertEquals("pub-1", publication.get("pubIdExact"));
    }

    /**
     * STO-7: the defect was that one statement grew with the document - 5,530 clauses for a
     * 553-node ontology - and Neo4j exhausted its heap planning it. The property that has
     * to hold is not "fewer clauses in total" (a document needs one write per element) but "a
     * statement's text does not grow with the element count". Ten times the species must produce the
     * same statement texts, more blocks, and never a bigger one.
     */
    @Test
    void keepsEveryStatementBoundedHoweverLargeTheDocumentIs() {
        var small = statements(ParsedModelGraphCypher.prepare(graphWithSpecies(10)));
        var large = statements(ParsedModelGraphCypher.prepare(graphWithSpecies(1000)));

        assertEquals(longestStatement(small), longestStatement(large),
                "a statement's text must not depend on how many elements the document has");
        assertTrue(longestStatement(large) < 1_000,
                "the longest statement stays a fixed template, was " + longestStatement(large) + " chars");
        assertTrue(large.size() > small.size(), "a larger document needs more blocks, not longer ones");
        BatchedGraphWrite.chunks(java.util.stream.IntStream.range(0, 1000).boxed().toList())
                .forEach(chunk -> assertEquals(
                        BatchedGraphWrite.BATCH_SIZE, chunk.size(), "only the last block may be short"));
    }

    /**
     * TEST-002: the rule above is asserted over nodes only, so a relationship block that grew with
     * its rows would keep the suite green. This fixture holds every node and both endpoints fixed and
     * changes one thing: how many relationship rows there are.
     */
    @Test
    void relationshipCountScalingKeepsEveryStatementByteEqual() {
        var oneBlock = ParsedModelGraphCypher.prepare(graphWithRelationships(250));
        var twoBlocks = ParsedModelGraphCypher.prepare(graphWithRelationships(500));
        var aboveTheMultiple = ParsedModelGraphCypher.prepare(graphWithRelationships(501));

        assertEquals(oneBlock.nodes().size(), twoBlocks.nodes().size(),
                "the fixture must change the relationship count and nothing else");
        assertEquals(1, relationshipBlocks(oneBlock, "IS_ENTITY_OF"), "250 rows is one block");
        assertEquals(2, relationshipBlocks(twoBlocks, "IS_ENTITY_OF"), "500 rows is two blocks");
        assertEquals(3, relationshipBlocks(aboveTheMultiple, "IS_ENTITY_OF"),
                "501 rows is three: the block count is the row count divided by 250, rounded up");
        assertEquals(longestStatement(statements(oneBlock)), longestStatement(statements(twoBlocks)),
                "a statement's text must not depend on how many relationships the document has");
        assertEquals(relationshipStatement(oneBlock, "IS_ENTITY_OF"), relationshipStatement(twoBlocks, "IS_ENTITY_OF"),
                "the two blocks carry the same text; only their rows differ");
    }

    /** Every statement of one relationship type, so a test can count the blocks it was cut into. */
    private long relationshipBlocks(BatchedGraphWrite write, String type) {
        return statements(write).stream().filter(statement -> statement.contains("[relationship:`" + type + "`]")).count();
    }

    /** The one text every block of {@code type} carries, asserted by the callers to be a single text. */
    private String relationshipStatement(BatchedGraphWrite write, String type) {
        var texts = statements(write).stream()
                .filter(statement -> statement.contains("[relationship:`" + type + "`]"))
                .distinct()
                .toList();
        assertEquals(1, texts.size(), "one relationship type is one statement text, cut into blocks: " + texts);
        return texts.get(0);
    }

    /**
     * One SED-ML surface pointing at one model reference, with {@code relationshipCount} edges between
     * them: the two entities and their node blocks are the same at every count.
     */
    private ExtractedModelGraph graphWithRelationships(int relationshipCount) {
        var entities = List.of(
                new ParsedModelEntity(
                        ParsedModelEntity.EntityType.SEDML_SURFACE,
                        "surface1",
                        "surface1",
                        Map.of("id", "surface1")),
                new ParsedModelEntity(
                        ParsedModelEntity.EntityType.SEDML_MODELREFERENCE,
                        "model1",
                        "model1",
                        Map.of("id", "model1")));
        var relationships = new ArrayList<ParsedEntityRelationship>(relationshipCount);
        for (var index = 0; index < relationshipCount; index++) {
            relationships.add(new ParsedEntityRelationship(
                    "SEDML_SURFACE:surface1",
                    "IS_ENTITY_OF",
                    "SEDML_MODELREFERENCE:model1",
                    Map.of()));
        }
        return new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.sedml", URI.create("https://example.org/example.sedml"), ModelType.SEDML),
                "m1",
                "Example model",
                Map.of(),
                entities,
                List.of(),
                List.of(),
                List.of(),
                relationships));
    }

    /** Every statement the write emits, in execution order, with placeholder element ids. */
    private List<String> statements(BatchedGraphWrite write) {
        var statements = new ArrayList<String>();
        statements.add(write.document().cypher());
        write.nodes().forEach(statement -> statements.add(statement.cypher()));
        var elementIdsByKey = new java.util.HashMap<String, String>();
        elementIdsByKey.put("document", "document-element-id");
        nodeRows(write).forEach(row -> elementIdsByKey.put(row.get("nodeKey").toString(), "element-id"));
        write.relationshipStatements(elementIdsByKey).forEach(statement -> statements.add(statement.cypher()));
        return statements;
    }

    private int longestStatement(List<String> statements) {
        return statements.stream().mapToInt(String::length).max().orElse(0);
    }

    /** One SBML document with {@code speciesCount} species and nothing else, so the count is the only variable. */
    private ExtractedModelGraph graphWithSpecies(int speciesCount) {
        var entities = new ArrayList<ParsedModelEntity>(speciesCount);
        for (var index = 0; index < speciesCount; index++) {
            entities.add(new ParsedModelEntity(
                    ParsedModelEntity.EntityType.SPECIES,
                    "s" + index,
                    "Species " + index,
                    Map.of("id", "s" + index, "name", "Species " + index)));
        }
        return new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of(),
                entities,
                List.of()));
    }

    /** All statement text, so a test can assert that a construct is emitted without caring where. */
    private String allCypher(BatchedGraphWrite write) {
        return String.join("\n", statements(write));
    }

    /** The properties of the node row the mapper keyed {@code nodeKey}, so a test never depends on block order. */
    private Map<String, String> nodeProperties(BatchedGraphWrite write, String nodeKey) {
        return nodeRows(write).stream()
                .filter(row -> nodeKey.equals(row.get("nodeKey")))
                .findFirst()
                .map(row -> assertStringMap(row.get("properties")))
                .orElseThrow(() -> new AssertionError("no node " + nodeKey + " in " + allCypher(write)));
    }

    /** Every node row of the write, in document order, across all blocks. */
    private List<Map<String, Object>> nodeRows(BatchedGraphWrite write) {
        var rows = new ArrayList<Map<String, Object>>();
        write.nodes().forEach(statement -> assertInstanceOf(List.class, statement.parameters().get("rows"))
                .forEach(row -> {
                    assertInstanceOf(Map.class, row);
                    @SuppressWarnings("unchecked")
                    var typed = (Map<String, Object>) row;
                    rows.add(typed);
                }));
        return rows;
    }

    /** Every relationship row of one type, whatever block it was cut into. */
    private List<BatchedGraphWrite.Row> relationshipRows(BatchedGraphWrite write, String type) {
        return write.relationships().stream()
                .filter(batch -> batch.type().equals(type))
                .flatMap(batch -> batch.rows().stream())
                .toList();
    }

    private Map<String, String> assertStringMap(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, String>) value;
    }
}

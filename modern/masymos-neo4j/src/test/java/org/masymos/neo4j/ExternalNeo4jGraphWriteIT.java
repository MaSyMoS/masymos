package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.masymos.core.annotation.AnnotationEnrichmentService;
import org.masymos.core.annotation.AnnotationFetchResult;
import org.masymos.core.annotation.AnnotationTextExtractor;
import org.masymos.core.annotation.AnnotationUriResolver;
import org.masymos.core.archive.CombineArchiveIndexingService;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.OwlOntologyParser;
import org.masymos.core.extract.ParsedAnnotation;
import org.masymos.core.extract.ParsedAnnotationResource;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.ParsedModelEntity;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.ParsedOntologyGraphMapper;
import org.masymos.core.extract.ParsedPerson;
import org.masymos.core.extract.ParsedPublication;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.extract.SedmlModelIndexingService;
import org.masymos.core.extract.SedmlModelParser;
import org.masymos.core.extract.UriModelSourceContentLoader;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;
import org.neo4j.driver.SessionConfig;

class ExternalNeo4jGraphWriteIT {
    @BeforeAll
    static void clearExternalNeo4jTestInstance() {
        var settings = settingsFromSystemProperties();
        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build())) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("DROP INDEX modelFulltext IF EXISTS").consume();
                tx.run("DROP INDEX modelFulltextV2 IF EXISTS").consume();
                tx.run("DROP INDEX modelFulltextV3 IF EXISTS").consume();
                tx.run("DROP INDEX modelFulltextV5 IF EXISTS").consume();
                tx.run("DROP INDEX annotationFulltextV1 IF EXISTS").consume();
                tx.run("DROP INDEX personFulltextV1 IF EXISTS").consume();
                tx.run("DROP INDEX publicationFulltextV1 IF EXISTS").consume();
            });
            session.executeWriteWithoutResult(tx -> tx.run("MATCH (node) DETACH DELETE node").consume());
        }
    }

    @Test
    void writesAndCleansExtractedSbmlGraphInExternalNeo4j() {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();
        var sourceUri = URI.create("urn:masymos:external-it:" + testRunId);

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build())) {
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                    new ModelReference("external-it-" + testRunId + ".sbml", sourceUri, ModelType.SBML),
                    "external_it",
                    "External integration test",
                    Map.of("testRunId", testRunId)
            ));

            try {
                var identifier = repository.insert(graph);

                var count = session.executeRead(tx -> tx.run("""
                        MATCH (document:Document {uid: $uid})-[:HAS_MODEL]->(:Model:SBML_MODEL)
                        WHERE document.sourceUri = $sourceUri
                        RETURN count(document) AS documentCount
                        """, Map.of(
                        "uid", identifier.uid(),
                        "sourceUri", sourceUri.toString()
                )).single().get("documentCount").asLong());

                assertEquals(1L, count);
            } finally {
                session.executeWrite(tx -> tx.run("""
                        MATCH (document:Document {sourceUri: $sourceUri})
                        OPTIONAL MATCH (document)-[*0..3]-(connected)
                        DETACH DELETE connected
                        """, Map.of("sourceUri", sourceUri.toString())).consume());
            }
        }
    }

    @Test
    void createsSedmlPostProcessingLinksInExternalNeo4j() {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build())) {
            try {
                session.executeWrite(tx -> tx.run("""
                        CREATE (ref:SEDML_MODELREFERENCE {id: 'ref', modelSource: 'urn:miriam:biomodels.db:BIOMD0000000001', testRunId: $testRunId})
                        CREATE (annotation:ANNOTATION {testRunId: $testRunId})
                        CREATE (resource:RESOURCE {uri: 'https://identifiers.org/biomodels.db/BIOMD0000000001', testRunId: $testRunId})
                        CREATE (model:SBML_MODEL {id: 'model', testRunId: $testRunId})
                        CREATE (species:SBML_SPECIES {id: 's1', testRunId: $testRunId})
                        CREATE (variable:SEDML_VARIABLE {id: 'var1', target: "/sbml:sbml/sbml:model/sbml:listOfSpecies/sbml:species[@id='s1']", testRunId: $testRunId})
                        CREATE (model)-[:HAS_ANNOTATION]->(annotation)
                        CREATE (annotation)-[:is]->(resource)
                        CREATE (model)-[:HAS_SPECIES]->(species)
                        CREATE (variable)-[:CALCULATES_MODEL]->(ref)
                        """, Map.of("testRunId", testRunId)).consume());

                var result = new Neo4jSedmlLinkingRepository(driver, settings.database()).linkSedmlReferences();

                assertEquals(1L, result.modelReferenceLinks());
                assertEquals(1L, result.sbmlObservationLinks());
                assertEquals(0L, result.cellmlObservationLinks());
            } finally {
                cleanupByTestRunId(session, testRunId);
            }
        }
    }

    @Test
    void runsOntologyTraversalQueriesInExternalNeo4j() {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build())) {
            try {
                session.executeWrite(tx -> tx.run("""
                        CREATE (root:TestRunOntology {id: 'owl:TestRunOntology', testRunId: $testRunId})
                        CREATE (parent:TestRunOntology {id: 'TERM_PARENT', testRunId: $testRunId})
                        CREATE (child:TestRunOntology {id: 'TERM_CHILD', testRunId: $testRunId})
                        CREATE (child)-[:isA]->(parent)
                        CREATE (parent)-[:isA]->(root)
                        """, Map.of("testRunId", testRunId)).consume());

                var rootQuery = OntologyQueryCypher.root("TestRunOntology");
                var depthQuery = OntologyQueryCypher.depth("TestRunOntology", "TERM_CHILD", 15);
                var pathQuery = OntologyQueryCypher.shortestPathLength("TestRunOntology", "TERM_PARENT", "TERM_CHILD", 15);

                assertEquals("owl:TestRunOntology", session.executeRead(tx -> tx.run(
                        rootQuery.cypher(),
                        rootQuery.parameters()
                ).single().get("term").get("id").asString()));
                long depth = session.executeRead(tx -> tx.run(
                        depthQuery.cypher(),
                        depthQuery.parameters()
                ).single().get("depth").asLong());
                long pathLength = session.executeRead(tx -> tx.run(
                        pathQuery.cypher(),
                        pathQuery.parameters()
                ).single().get("pathLength").asLong());

                assertEquals(2L, depth);
                assertEquals(1L, pathLength);
            } finally {
                cleanupByTestRunId(session, testRunId);
            }
        }
    }

    @Test
    void queriesExtractedModelThroughFulltextIndexInExternalNeo4j() {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();
        var sourceUri = URI.create("urn:masymos:external-fulltext-it:" + testRunId);
        var sedmlSourceUri = URI.create("urn:masymos:external-sedml-fulltext-it:" + testRunId);
        var marker = "externalQueryMarker" + testRunId.replace("-", "");
        var sedmlMarker = "sedmlSourceMarker" + testRunId.replace("-", "");

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build())) {
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var resourceQueryRepository = new Neo4jResourceQueryRepository(driver, settings.database());
            var graph = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                    new ModelReference("external-query-" + testRunId + ".sbml", sourceUri, ModelType.SBML),
                    "external_query_it",
                    marker,
                    Map.of("testRunId", testRunId),
                    List.of(
                            new ParsedModelEntity(
                                    ParsedModelEntity.EntityType.SPECIES,
                                    "query_species_" + marker,
                                    "query species " + marker,
                                    Map.of("id", "query_species_" + marker, "name", "query species " + marker)
                            ),
                            new ParsedModelEntity(
                                    ParsedModelEntity.EntityType.REACTION,
                                    "query_reaction_" + marker,
                                    "query reaction " + marker,
                                    Map.of("id", "query_reaction_" + marker, "name", "query reaction " + marker)
                            ),
                            new ParsedModelEntity(
                                    ParsedModelEntity.EntityType.PARAMETER,
                                    "query_parameter_" + marker,
                                    "query parameter " + marker,
                                    Map.of("id", "query_parameter_" + marker, "name", "query parameter " + marker)
                            )
                    ),
                    List.of(),
                    List.of(new ParsedAnnotation(
                            "model",
                            Map.of(),
                            List.of(new ParsedAnnotationResource("https://identifiers.org/go/GO:" + marker, "is")),
                            List.of(new ParsedPerson("Creator", marker, marker + "@example.org", "Query Organization " + marker)),
                            List.of()
                    )),
                    List.of(new ParsedPublication(
                            "query-pub-" + marker,
                            "Publication Title " + marker,
                            "Journal " + marker,
                            "",
                            "",
                            "2026",
                            List.of(new ParsedPerson("Author", marker, "", ""))
                    ))
            ));

            try {
                new Neo4jSchemaInitializer(driver, settings.database()).initialize();
                repository.insert(graph);
                repository.insert(new ParsedModelGraphMapper().toGraph(new ParsedModel(
                        new ModelReference("external-query-" + testRunId + ".sedml", sedmlSourceUri, ModelType.SEDML),
                        "external_sedml_query_it",
                        "External SED-ML query test",
                        Map.of("testRunId", testRunId),
                        List.of(new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_MODELREFERENCE,
                                "sedml_model_" + marker,
                                "SED-ML model " + marker,
                                Map.of(
                                        "id",
                                        "sedml_model_" + marker,
                                        "name",
                                        "SED-ML model " + marker,
                                        "modelSource",
                                        sedmlMarker,
                                        "modelChanged",
                                        "false"
                                )
                        )),
                        List.of()
                )));
                session.executeWriteWithoutResult(tx -> tx.run("CALL db.awaitIndexes(30)").consume());

                var results = queryRepository.queryModels(new ModelQueryRequest(marker, List.of(), 5));

                assertEquals(1, results.stream().filter(result -> result.documentUri().equals(sourceUri)).count());
                assertQueryFinds(queryRepository, sourceUri, QueryField.CREATOR, marker);
                assertQueryFinds(queryRepository, sourceUri, QueryField.AUTHOR, marker);
                assertQueryFinds(queryRepository, sourceUri, QueryField.TITLE, marker);
                assertQueryFinds(queryRepository, sourceUri, QueryField.JOURNAL, marker);
                assertQueryFinds(queryRepository, sourceUri, QueryField.SPECIES, "query_species_" + marker);
                assertQueryFinds(queryRepository, sourceUri, QueryField.REACTION, "query_reaction_" + marker);
                assertQueryFinds(queryRepository, sourceUri, QueryField.PARAMETER, "query_parameter_" + marker);
                assertQueryFinds(queryRepository, sourceUri, QueryField.URI, sourceUri.toString());
                assertQueryFinds(queryRepository, sourceUri, QueryField.RESOURCE_URI, "GO:" + marker);
                assertQueryFinds(queryRepository, sedmlSourceUri, QueryField.MODEL_SOURCE, sedmlMarker);
                // QA-7: the exact clause is an equality on the stored value, and the resolver only
                // expands MIRIAM/identifiers.org/HTTP forms - a bare "GO:<id>" is neither, so it
                // matched only through the model full-text lane above, never through uriExact.
                org.junit.jupiter.api.Assertions.assertTrue(resourceQueryRepository.queryAnnotations(new ModelQueryRequest(
                        "",
                        List.of(new QueryClause(QueryField.RESOURCE_URI, "https://identifiers.org/go/GO:" + marker)),
                        5
                )).stream().anyMatch(result -> result.relatedModelUris().contains(sourceUri)));
                org.junit.jupiter.api.Assertions.assertTrue(resourceQueryRepository.queryPersons(new ModelQueryRequest(
                        "",
                        List.of(new QueryClause(QueryField.FAMILYNAME, marker)),
                        5
                )).stream().anyMatch(result -> result.relatedModelUris().contains(sourceUri)));
                org.junit.jupiter.api.Assertions.assertTrue(resourceQueryRepository.queryPublications(new ModelQueryRequest(
                        "",
                        List.of(new QueryClause(QueryField.TITLE, marker)),
                        5
                )).stream().anyMatch(result -> result.relatedModelUris().contains(sourceUri)));
            } finally {
                session.executeWrite(tx -> tx.run("""
                        MATCH (document:Document)
                        WHERE document.sourceUri IN [$sourceUri, $sedmlSourceUri]
                        OPTIONAL MATCH (document)-[*0..3]-(connected)
                        DETACH DELETE connected
                        """, Map.of(
                        "sourceUri", sourceUri.toString(),
                        "sedmlSourceUri", sedmlSourceUri.toString()
                )).consume());
            }
        }
    }

    @Test
    void rebuildsAnnotationResourceTextIndexInExternalNeo4j() {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();
        var sourceUri = URI.create("urn:masymos:external-annotation-index-it:" + testRunId);
        var resourceUri = "https://example.org/resource/" + testRunId;
        var resourceText = "resolved annotation marker " + testRunId.replace("-", "");

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build())) {
            try {
                session.executeWriteWithoutResult(tx -> tx.run("""
                        CREATE (document:Document {uid: randomUUID(), fileId: 'annotation-it.xml', sourceUri: $sourceUri})
                        CREATE (model:Model:SBML_MODEL {modelId: 'annotation_it', name: 'Annotation IT'})
                        CREATE (annotation:ANNOTATION)
                        CREATE (resource:RESOURCE {uri: $resourceUri})
                        CREATE (document)-[:HAS_MODEL]->(model)
                        CREATE (model)-[:HAS_ANNOTATION]->(annotation)
                        CREATE (annotation)-[:is]->(resource)
                        """, Map.of(
                        "sourceUri", sourceUri.toString(),
                        "resourceUri", resourceUri
                )).consume());

                var result = new Neo4jAnnotationIndexService(
                        driver,
                        settings.database(),
                        new AnnotationEnrichmentService(
                                new AnnotationUriResolver(),
                                url -> resourceUri.equals(url)
                                        ? new AnnotationFetchResult(url, 200, "text/plain", resourceText, null)
                                        : AnnotationFetchResult.failure(url, "http status 404"),
                                new AnnotationTextExtractor()
                        )
                ).rebuild(false);

                org.junit.jupiter.api.Assertions.assertTrue(result.scannedResources() >= 1L);
                assertEquals(1L, result.indexedResources());
                assertEquals(result.scannedResources() - 1L, result.skippedResources());
                assertEquals(0L, result.failedResources());
                assertEquals(1L, count(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(model:Model)-[:HAS_ANNOTATION]->(:ANNOTATION)-[:is]->(resource:RESOURCE)
                        WHERE resource.resourceText = $resourceText
                          AND resource.isIndexed = true
                          AND model.resourceText = $resourceText
                        RETURN count(*) AS count
                        """, Map.of(
                        "sourceUri", sourceUri.toString(),
                        "resourceText", resourceText
                )));
            } finally {
                cleanupBySourceUri(session, sourceUri);
            }
        }
    }

    @Test
    void writesRealCellmlSampleGraphInExternalNeo4j() throws Exception {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();
        var sample = Path.of("..", "..", "samples", "cellml", "downloaded", "schmid_nash_young_hunter_2006_a.cellml");
        Assumptions.assumeTrue(Files.isRegularFile(sample), "CellML sample is required");
        var sourceUri = URI.create("urn:masymos:external-cellml-sample-it:" + testRunId);

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build());
                var stream = Files.newInputStream(sample)) {
            var parsed = new CellmlModelParser().parse(new ModelSource(new ModelReference(
                    "external-cellml-" + testRunId + ".cellml",
                    sourceUri,
                    ModelType.CELLML
            )), stream);
            var graph = new ParsedModelGraphMapper().toGraph(parsed);

            try {
                new Neo4jExtractedModelGraphRepository(driver, settings.database()).insert(graph);

                assertEquals(1L, count(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model:CELLML_MODEL)
                        RETURN count(*) AS count
                        """, sourceUri));
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_COMPONENT]->(:CELLML_COMPONENT)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_VARIABLE]->(:CELLML_VARIABLE)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[*1..3]-(:CELLML_VARIABLE)-[:IS_CONNECTED_TO]->(:CELLML_VARIABLE)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_PUBLICATION]->(:PUBLICATION)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_PUBLICATION]->(:PUBLICATION)-[:HAS_AUTHOR]->(:PERSON)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_ANNOTATION]->(:ANNOTATION)-[:IS_CREATOR]->(:PERSON)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
            } finally {
                cleanupBySourceUri(session, sourceUri);
            }
        }
    }

    @Test
    void writesRealSedmlSampleGraphInExternalNeo4j() throws Exception {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();
        var sample = Path.of("..", "..", "samples", "sedml", "BIOMD0000000003sedml.xml");
        Assumptions.assumeTrue(Files.isRegularFile(sample), "SED-ML sample is required");
        var sourceUri = URI.create("urn:masymos:external-sedml-sample-it:" + testRunId);

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build());
                var stream = Files.newInputStream(sample)) {
            var parsed = new SedmlModelParser().parse(new ModelSource(new ModelReference(
                    "external-sedml-" + testRunId + ".sedml",
                    sourceUri,
                    ModelType.SEDML
            )), stream);
            var graph = new ParsedModelGraphMapper().toGraph(parsed);

            try {
                new Neo4jSchemaInitializer(driver, settings.database()).initialize();
                new Neo4jExtractedModelGraphRepository(driver, settings.database()).insert(graph);
                var queryRepository = new Neo4jModelRepository(driver, settings.database());
                session.executeWriteWithoutResult(tx -> tx.run("CALL db.awaitIndexes(30)").consume());

                assertEquals(1L, count(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model:SEDML_MODEL)
                        RETURN count(*) AS count
                        """, sourceUri));
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_MODELREFERENCE]->(:SEDML_MODELREFERENCE)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_VARIABLE]->(:SEDML_VARIABLE)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_SIMULATION]->(:SEDML_SIMULATION)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(model:Model)
                        WHERE model.modelSource = 'urn:miriam:biomodels.db:BIOMD0000000003'
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
            } finally {
                cleanupBySourceUri(session, sourceUri);
            }
        }
    }

    @Test
    void linksRealSedmlSampleToRealSbmlSampleInExternalNeo4j() throws Exception {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();
        var sbmlSample = Path.of("..", "..", "samples", "sbml", "BIOMD0000000005.xml");
        var sedmlSample = Path.of("..", "..", "samples", "sedml", "BIOMD0000000005sedml_a.xml");
        Assumptions.assumeTrue(Files.isRegularFile(sbmlSample), "SBML sample is required");
        Assumptions.assumeTrue(Files.isRegularFile(sedmlSample), "SED-ML sample is required");
        var sbmlSourceUri = URI.create("urn:masymos:external-cross-sbml-it:" + testRunId);
        var sedmlSourceUri = URI.create("urn:masymos:external-cross-sedml-it:" + testRunId);

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build());
                var sbmlStream = Files.newInputStream(sbmlSample);
                var sedmlStream = Files.newInputStream(sedmlSample)) {
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            repository.insert(new ParsedModelGraphMapper().toGraph(new SbmlModelParser().parse(new ModelSource(new ModelReference(
                    "external-cross-" + testRunId + ".xml",
                    sbmlSourceUri,
                    ModelType.SBML
            )), sbmlStream)));
            repository.insert(new ParsedModelGraphMapper().toGraph(new SedmlModelParser().parse(new ModelSource(new ModelReference(
                    "external-cross-" + testRunId + ".sedml",
                    sedmlSourceUri,
                    ModelType.SEDML
            )), sedmlStream)));

            try {
                var result = new Neo4jSedmlLinkingRepository(driver, settings.database()).linkSedmlReferences();

                org.junit.jupiter.api.Assertions.assertTrue(result.modelReferenceLinks() >= 1);
                org.junit.jupiter.api.Assertions.assertTrue(result.sbmlObservationLinks() >= 1);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sedmlSourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_MODELREFERENCE]->(:SEDML_MODELREFERENCE)-[:REFERENCES_SIMULATION_MODEL]->(:SBML_MODEL)<-[:HAS_MODEL]-(:Document {sourceUri: $sbmlSourceUri})
                        RETURN count(*) AS count
                        """, Map.of("sedmlSourceUri", sedmlSourceUri.toString(), "sbmlSourceUri", sbmlSourceUri.toString()), 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sedmlSourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_VARIABLE]->(:SEDML_VARIABLE)-[:OBSERVES]->(:SBML_SPECIES)
                        RETURN count(*) AS count
                        """, Map.of("sedmlSourceUri", sedmlSourceUri.toString()), 1L);
            } finally {
                cleanupBySourceUri(session, sedmlSourceUri);
                cleanupBySourceUri(session, sbmlSourceUri);
            }
        }
    }

    @Test
    void writesRealOwlSampleGraphInExternalNeo4j() throws Exception {
        var settings = settings();
        var testRunId = UUID.randomUUID().toString();
        var sample = Path.of("..", "..", "samples", "owl", "kisao.owl");
        Assumptions.assumeTrue(Files.isRegularFile(sample), "OWL sample is required");
        var sourceUri = URI.create("urn:masymos:external-owl-sample-it:" + testRunId);

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build());
                var stream = Files.newInputStream(sample)) {
            var parsed = new OwlOntologyParser().parse(new ModelSource(new ModelReference(
                    "external-kisao-" + testRunId + ".owl",
                    sourceUri,
                    ModelType.OWL
            )), stream);
            var graph = new ParsedOntologyGraphMapper().toGraph(parsed);

            try {
                new Neo4jExtractedModelGraphRepository(driver, settings.database()).insert(graph);

                assertEquals(1L, count(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model:OWL_MODEL)
                        RETURN count(*) AS count
                        """, sourceUri));
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_ONTOLOGY_TERM]->(:KISAOOntology)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
                assertAtLeast(session, """
                        MATCH (:Document {sourceUri: $sourceUri})-[:HAS_MODEL]->(:Model)-[:HAS_ONTOLOGY_TERM]->(:KISAOOntology)-[:isA]->(:KISAOOntology)
                        RETURN count(*) AS count
                        """, sourceUri, 1L);
            } finally {
                cleanupBySourceUri(session, sourceUri);
            }
        }
    }

    private static void cleanupByTestRunId(org.neo4j.driver.Session session, String testRunId) {
        session.executeWrite(tx -> tx.run("""
                MATCH (node {testRunId: $testRunId})
                DETACH DELETE node
                """, Map.of("testRunId", testRunId)).consume());
    }

    private static long count(org.neo4j.driver.Session session, String cypher, URI sourceUri) {
        return session.executeRead(tx -> tx.run(cypher, Map.of("sourceUri", sourceUri.toString()))
                .single()
                .get("count")
                .asLong());
    }

    private static long count(org.neo4j.driver.Session session, String cypher, Map<String, Object> parameters) {
        return session.executeRead(tx -> tx.run(cypher, parameters).single().get("count").asLong());
    }

    private static void assertAtLeast(org.neo4j.driver.Session session, String cypher, URI sourceUri, long minimum) {
        var actual = count(session, cypher, sourceUri);
        org.junit.jupiter.api.Assertions.assertTrue(actual >= minimum, "expected at least " + minimum + " but got " + actual);
    }

    private static void assertAtLeast(
            org.neo4j.driver.Session session,
            String cypher,
            Map<String, Object> parameters,
            long minimum
    ) {
        var actual = session.executeRead(tx -> tx.run(cypher, parameters).single().get("count").asLong());
        org.junit.jupiter.api.Assertions.assertTrue(actual >= minimum, "expected at least " + minimum + " but got " + actual);
    }

    private static void assertQueryFinds(
            Neo4jModelRepository queryRepository,
            URI sourceUri,
            QueryField field,
            String keyword
    ) {
        var results = queryRepository.queryModels(new ModelQueryRequest("", List.of(new QueryClause(field, keyword)), 5));
        assertEquals(1, results.stream().filter(result -> result.documentUri().equals(sourceUri)).count(), field.name());
    }

    @Test
    void indexesRealCombineArchiveAndKeepsItAboveItsDocuments() throws Exception {
        var settings = settings();
        var archive = Path.of("..", "masymos-core", "src", "test", "resources", "jjj-variety", "adlung2017_fig2g.omex");
        Assumptions.assumeTrue(Files.isRegularFile(archive), "JJJ COMBINE archive fixture is required");
        var archiveUri = archive.toAbsolutePath().toUri();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build())) {
            try {
                var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
                var discovery = new ModelSourceDiscovery();
                var contentLoader = new UriModelSourceContentLoader();
                var graphMapper = new ParsedModelGraphMapper();
                var service = new CombineArchiveIndexingService(
                        new SbmlModelIndexingService(discovery, contentLoader, new SbmlModelParser(), graphMapper, repository),
                        new CellmlModelIndexingService(discovery, contentLoader, new CellmlModelParser(), graphMapper, repository),
                        new SedmlModelIndexingService(discovery, contentLoader, new SedmlModelParser(), graphMapper, repository),
                        repository);

                var result = service.index(archiveUri);

                assertEquals(3, result.indexedEntries(), "two models plus one simulation description");
                assertEquals(0, result.skipped().size() - 1, "only the metadata entry is skipped: " + result.skipped());
                assertEquals(1L, count(session, """
                        MATCH (archive:CombineArchive {sourceUri: $uri}) RETURN count(archive) AS count
                        """, Map.of("uri", archiveUri.toString())));
                assertEquals(3L, count(session, """
                        MATCH (:CombineArchive {sourceUri: $uri})-[:HAS_DOCUMENT]->(document:Document)
                        RETURN count(document) AS count
                        """, Map.of("uri", archiveUri.toString())));
                assertEquals(2L, count(session, """
                        MATCH (:CombineArchive {sourceUri: $uri})-[:HAS_DOCUMENT]->(:Document)-[:HAS_MODEL]->(:Model:SBML_MODEL)
                        RETURN count(*) AS count
                        """, Map.of("uri", archiveUri.toString())));
                assertAtLeast(session, """
                        MATCH (reference:SEDML_MODELREFERENCE)
                        WHERE reference.resolvedSource ENDS WITH '!/models/adlung1.sbml'
                        RETURN count(reference) AS count
                        """, archiveUri, 1L);
            } finally {
                session.executeWriteWithoutResult(tx -> tx.run("""
                        MATCH (document:Document)
                        WHERE document.sourceUri STARTS WITH $prefix
                        OPTIONAL MATCH (document)-[*0..]-(connected)
                        WITH DISTINCT connected
                        DETACH DELETE connected
                        """, Map.of("prefix", archiveUri + "!/")).consume());
                session.executeWriteWithoutResult(tx -> tx.run("""
                        MATCH (archive:CombineArchive {sourceUri: $uri}) DETACH DELETE archive
                        """, Map.of("uri", archiveUri.toString())).consume());
            }
        }
    }

    private static void cleanupBySourceUri(org.neo4j.driver.Session session, URI sourceUri) {
        // Unbounded traversal on purpose: annotation graphs chain
        // Document -> Model -> ANNOTATION -> RESOURCE -> RESOURCE (isVersionOf/hasVersion),
        // so a hop limit leaves orphaned RESOURCE/ANNOTATION/PERSON nodes behind.
        session.executeWrite(tx -> tx.run("""
                MATCH (document:Document {sourceUri: $sourceUri})
                MATCH path = (document)-[*0..]-(connected)
                WITH DISTINCT connected
                DETACH DELETE connected
                """, Map.of("sourceUri", sourceUri.toString())).consume());
    }

    private Neo4jSettings settings() {
        return settingsFromSystemProperties();
    }

    private static Neo4jSettings settingsFromSystemProperties() {
        var uri = System.getProperty("masymos.it.neo4j.uri");
        var username = System.getProperty("masymos.it.neo4j.username");
        var password = System.getProperty("masymos.it.neo4j.password");
        var database = System.getProperty("masymos.it.neo4j.database", "neo4j");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "masymos.it.neo4j.uri is required");
        Assumptions.assumeTrue(username != null && !username.isBlank(), "masymos.it.neo4j.username is required");
        Assumptions.assumeTrue(password != null && !password.isBlank(), "masymos.it.neo4j.password is required");
        return new Neo4jSettings(URI.create(uri), username, password, database);
    }
}

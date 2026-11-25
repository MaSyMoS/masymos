package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.dockerjava.api.model.HostConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.masymos.core.annotation.AnnotationFetchResult;
import org.masymos.core.annotation.AnnotationPrefixCatalogue;
import org.masymos.core.annotation.AnnotationTermCache;
import org.masymos.core.annotation.AnnotationTermProvider;
import org.masymos.core.annotation.AnnotationTermResolver;
import org.masymos.core.annotation.AnnotationTextExtractor;
import org.masymos.core.annotation.ResolverMode;
import org.masymos.core.annotation.TermDefinition;
import org.masymos.core.annotation.TermKey;
import org.masymos.core.extract.CellmlExternalCmetaResolver;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ExtractedGraphNode;
import org.masymos.core.extract.ExtractedGraphRelationship;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.FileCellmlImportResolver;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.OwlOntologyIndexingService;
import org.masymos.core.extract.OwlOntologyParser;
import org.masymos.core.extract.ParsedAnnotation;
import org.masymos.core.extract.ParsedAnnotationResource;
import org.masymos.core.extract.ParsedEntityRelationship;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.ParsedModelEntity;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.ParsedOntologyGraphMapper;
import org.masymos.core.extract.ParsedReactionParticipant;
import org.masymos.core.extract.RemotePmrCellmlExternalCmetaResolver;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.extract.SourceDocument;
import org.masymos.core.extract.UriModelSourceContentLoader;
import org.masymos.core.model.AnnotationIndexingOptions;
import org.masymos.core.model.ModelConflictException;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.ModelVersionHit;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryEntity;
import org.masymos.core.query.QueryField;
import org.masymos.core.query.fusion.AggregatedQuery;
import org.masymos.core.query.fusion.BestRankStrategy;
import org.masymos.core.query.fusion.BordaCombMnzStrategy;
import org.masymos.core.query.fusion.FusedModelHit;
import org.masymos.core.query.fusion.FusionSource;
import org.masymos.core.query.fusion.FusionWeights;
import org.masymos.core.query.fusion.ModelRow;
import org.masymos.core.query.fusion.RankFusionQueryService;
import org.masymos.core.query.fusion.Ranker;
import org.masymos.core.spi.UnitBackfill;
import org.masymos.core.units.CanonicalUnit;
import org.masymos.core.units.UnitComponent;
import org.masymos.core.units.UnitEntityBuilder;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Self-contained Neo4j integration test: the database runs in a Testcontainers-managed
 * container, so no external instance is required. Only executed by Maven Failsafe under the
 * testcontainers-it profile, which includes the pattern star star slash star ContainersIT.java.
 * Routine mvn verify neither sees the Testcontainers dependencies nor runs this class.
 */
@Testcontainers(disabledWithoutDocker = true)
class Neo4jContainersIT {
    private static final String TAG = "containers-it";

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:2026.05")
            .withoutAuthentication()
            // Docker 19.03 (LinuxKit 4.19) ships a seccomp profile without clone3, which glibc >= 2.34
            // uses to create threads; JVM containers otherwise die with pthread_create (EPERM).
            .withCreateContainerCmdModifier(cmd -> {
                var hostConfig = cmd.getHostConfig() == null ? HostConfig.newHostConfig() : cmd.getHostConfig();
                cmd.withHostConfig(hostConfig.withSecurityOpts(List.of("seccomp=unconfined")));
            });

    @Test
    void writesAndReadsSmokeCheckNodeInContainer() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session()) {
            driver.verifyConnectivity();

            try {
                var uid = session.executeWrite(tx -> tx.run(
                        "CREATE (n:SmokeCheck {uid: $uid}) RETURN n.uid",
                        Map.of("uid", TAG)
                ).single().get("n.uid").asString());

                assertEquals(TAG, uid);
                long afterInsert = session.executeRead(tx -> tx.run(
                        "MATCH (n:SmokeCheck {uid: $uid}) RETURN count(n) AS count",
                        Map.of("uid", TAG)
                ).single().get("count").asLong());
                assertEquals(1L, afterInsert);
            } finally {
                session.executeWrite(tx -> tx.run(
                        "MATCH (n:SmokeCheck {uid: $uid}) DETACH DELETE n",
                        Map.of("uid", TAG)
                ).consume());
            }

            long afterCleanup = session.executeRead(tx -> tx.run(
                    "MATCH (n:SmokeCheck {uid: $uid}) RETURN count(n) AS count",
                    Map.of("uid", TAG)
            ).single().get("count").asLong());
            assertEquals(0L, afterCleanup);
        }
    }

    /**
     * TEST-001: a document write is one transaction, so a statement that fails after the prologue
     * leaves neither the document node nor any node the write owned. The batch writer's own
     * missing-endpoint guard cannot be reached through
     * {@link Neo4jExtractedModelGraphRepository#insert}: {@code ParsedModelGraphCypher.validateGraph}
     * refuses an endpoint the graph never names before a transaction exists. The failure here is
     * therefore a real server refusal - a second {@code Document} node whose {@code uid} collides
     * with a node already in the store, which the {@code document_uid} uniqueness constraint rejects
     * only after the prologue and the first node block have run.
     */
    @Test
    void aFailureAfterThePrologueLeavesNoNodeOfTheDocument() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-rollback.sbml";
        var existingFileId = "containers-it-rollback-existing.sbml";
        var existingUid = "containers-it-rollback-existing";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());

            try {
                session.executeWrite(tx -> tx.run("""
                        CREATE (:Document {uid: $uid, fileId: $fileId})
                        """, Map.of("uid", existingUid, "fileId", existingFileId)).consume());
                var counterBefore = uniqueIdCounter(session);
                var ownedBefore = ownedNodeCount(session);

                var graph = new ExtractedModelGraph(
                        new ModelReference(
                                fileId,
                                URI.create("urn:masymos:containers-it:rollback"),
                                ModelType.SBML,
                                "v1"),
                        List.of(
                                new ExtractedGraphNode(
                                        "document",
                                        Set.of("Document"),
                                        Map.of(
                                                "fileId", fileId,
                                                "versionId", "v1",
                                                "sourceUri", "urn:masymos:containers-it:rollback")),
                                new ExtractedGraphNode(
                                        "model",
                                        Set.of("Model", "SBML_MODEL"),
                                        Map.of(
                                                "fileId", fileId,
                                                "modelId", "model_" + fileId,
                                                "versionId", "v1",
                                                "sourceUri", "urn:masymos:containers-it:rollback")),
                                new ExtractedGraphNode(
                                        "collision", Set.of("Document"), Map.of("uid", existingUid))),
                        List.of(new ExtractedGraphRelationship("document", "HAS_MODEL", "model", Map.of())));

                // The document statement and the model node run before this one; the constraint
                // refusal must take them with it when the transaction aborts.
                var failure = assertThrows(RuntimeException.class, () -> repository.insert(graph));

                // A fresh session: a count read must never see the aborted transaction's writes.
                try (var reader = driver.session(sessionConfig)) {
                    assertEquals(
                            0L,
                            documentCount(reader, fileId, "v1"),
                            () -> "the failed write left its document node behind: " + failure);
                    assertEquals(
                            ownedBefore,
                            ownedNodeCount(reader),
                            () -> "the failed write left a node it owned behind: " + failure);
                    assertEquals(
                            counterBefore,
                            uniqueIdCounter(reader),
                            "the prologue's counter bump must roll back with the rest of the transaction");
                }
            } finally {
                deleteFile(session, existingFileId);
                var mintedUids = documentOwnerUids(session, fileId);
                deleteFile(session, fileId);
                mintedUids.forEach(uid -> session.executeWriteWithoutResult(tx -> tx.run(
                        "MATCH (n) WHERE n.ownerUid = $uid DETACH DELETE n", Map.of("uid", uid)).consume()));
            }
        }
    }

    /**
     * The versioned identity against a real server: three inserts of one fileId must form one chain,
     * the latest-version lookup must return the third, and a legacy numeric uid must still resolve.
     * It also proves the schema DDL of the change — the GlobalUniqueId counter constraint, the
     * file/version indexes and the modelFulltextV6 generation.
     */
    @Test
    void keepsThreeVersionsOfOneFileInOneChain() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-chain.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                for (var version : List.of("v1", "v2", "v3")) {
                    repository.insert(mapper.toGraph(new ParsedModel(
                            new ModelReference(
                                    fileId,
                                    URI.create("urn:masymos:containers-it:" + version),
                                    ModelType.SBML,
                                    version
                            ),
                            "containers_it",
                            "Containers IT " + version,
                            Map.of()
                    )));
                }

                long successors = session.executeRead(tx -> tx.run("""
                        MATCH (:Document {fileId: $fileId})-[:HAS_SUCCESSOR]->(:Document {fileId: $fileId})
                        RETURN count(*) AS count
                        """, Map.of("fileId", fileId)).single().get("count").asLong());
                assertEquals(2L, successors);
                assertEquals("v3", queryRepository.findLatestVersion(fileId).orElseThrow().versionId());
                assertEquals(
                        List.of("v1", "v2", "v3"),
                        queryRepository.findVersions(fileId).stream().map(version -> version.versionId()).toList());
                assertEquals("v2", queryRepository.findVersion(fileId, "v2").orElseThrow().versionId());

                var firstLegacyUid = session.executeRead(tx -> tx.run("""
                        MATCH (d:Document {fileId: $fileId, versionId: 'v1'})
                        RETURN d.legacyUid AS legacyUid
                        """, Map.of("fileId", fileId)).single().get("legacyUid").asLong());
                assertEquals("v1", queryRepository.findByUid(Long.toString(firstLegacyUid))
                        .orElseThrow()
                        .versionId());
            } finally {
                session.executeWrite(tx -> tx.run("""
                        MATCH (d:Document {fileId: $fileId})
                        OPTIONAL MATCH (d)-[*0..]-(connected)
                        WITH DISTINCT connected
                        DETACH DELETE connected
                        """, Map.of("fileId", fileId)).consume());
            }
        }
    }

    /**
     * STO-2 against a real server: the document read back is the document that was indexed, an
     * oversized one is reported as truncated instead of silently shortened, the metadata map
     * survives, and both the canonical uid and the numeric legacy uid resolve it.
     */
    @Test
    void storesTheSourceDocumentAndServesItBackUnchanged() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-document.sbml";
        var content = "<sbml>Grüße</sbml>";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                var bytes = content.getBytes(StandardCharsets.UTF_8);
                var whole = repository.insert(mapper
                        .toGraph(new ParsedModel(
                                new ModelReference(
                                        fileId,
                                        URI.create("urn:masymos:containers-it:document"),
                                        ModelType.SBML,
                                        "v1",
                                        Map.of("submitter", "jj")),
                                "containers_document",
                                "Containers document",
                                Map.of()))
                        .withSourceDocument(SourceDocument.from(bytes, 1024)));

                var stored = queryRepository.findDocument(whole.uid()).orElseThrow();
                assertArrayEquals(bytes, stored.contentBytes());
                assertFalse(stored.truncated());
                assertEquals(bytes.length, stored.originalBytes());
                assertEquals(Map.of("submitter", "jj"), stored.metadata());
                assertEquals(
                        whole.uid(),
                        queryRepository.findDocument(Long.toString(whole.legacyUid()))
                                .orElseThrow()
                                .uid());

                var oversized = repository.insert(mapper
                        .toGraph(new ParsedModel(
                                new ModelReference(
                                        fileId,
                                        URI.create("urn:masymos:containers-it:document:truncated"),
                                        ModelType.SBML,
                                        "v2"),
                                "containers_document",
                                "Containers document",
                                Map.of()))
                        .withSourceDocument(SourceDocument.from(bytes, 6)));

                var truncated = queryRepository.findDocument(oversized.uid()).orElseThrow();
                assertTrue(truncated.truncated());
                assertEquals(bytes.length, truncated.originalBytes());
                assertEquals(6, truncated.contentBytes().length);
            } finally {
                session.executeWrite(tx -> tx.run("""
                        MATCH (d:Document {fileId: $fileId})
                        OPTIONAL MATCH (d)-[*0..]-(connected)
                        WITH DISTINCT connected
                        DETACH DELETE connected
                        """, Map.of("fileId", fileId)).consume());
            }
        }
    }

    /**
     * STO-4 against a real server: deleting a middle version leaves v1 -HAS_SUCCESSOR-> v3, removes
     * exactly the nodes that version owned, purges annotation nodes that lost their last
     * relationship, refuses a mismatching confirmation, and does not touch another document.
     */
    @Test
    void deletesAMiddleVersionRelinksTheChainAndLeavesOtherDocumentsIntact() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-delete.sbml";
        var otherFileId = "containers-it-keep.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                var versions = List.of("v1", "v2", "v3");
                var uids = new java.util.ArrayList<String>();
                for (var version : versions) {
                    uids.add(repository.insert(modelWithCompartment(mapper, fileId, version)).uid());
                }
                var other = repository.insert(modelWithCompartment(mapper, otherFileId, "v1"));
                var otherNodesBefore = ownedNodeCount(session, other.uid());
                session.executeWrite(tx -> tx.run("CREATE (:RESOURCE {uri: 'urn:masymos:orphan'})").consume());

                var middle = uids.get(1);
                var middleLegacyUid = queryRepository.findByUid(middle).orElseThrow().legacyUid();

                assertThrows(ModelConflictException.class, () ->
                        queryRepository.deleteByUid(Long.toString(middleLegacyUid), "wrong-file", "v2"));
                assertEquals(1L, documentCount(session, fileId, "v2"), "a refused delete must not remove anything");

                var deleted = queryRepository
                        .deleteByUid(Long.toString(middleLegacyUid), fileId, "v2")
                        .orElseThrow();

                assertEquals(1, deleted.documents());
                assertEquals(
                        List.of("v1", "v3"),
                        queryRepository.findVersions(fileId).stream().map(version -> version.versionId()).toList());
                assertEquals("v3", queryRepository.findLatestVersion(fileId).orElseThrow().versionId());
                assertEquals(1L, successorCount(session, fileId), "v1 must now point at v3");
                assertEquals(0L, ownedNodeCount(session, middle), "the deleted version owns no node any more");
                assertEquals(
                        otherNodesBefore,
                        ownedNodeCount(session, other.uid()),
                        "another document's graph must survive");
                assertEquals(0L, orphanCount(session, "RESOURCE"), "the unreferenced RESOURCE must be purged");
                assertEquals(0L, orphanCount(session, "PUBLICATION"), "the publication must be purged");
                assertEquals(0L, orphanCount(session, "PERSON"), "the author must be purged");
            } finally {
                deleteFile(session, fileId);
                deleteFile(session, otherFileId);
                session.executeWrite(tx -> tx.run(
                        "MATCH (n:RESOURCE {uri: 'urn:masymos:orphan'}) DETACH DELETE n").consume());
            }
        }
    }

    @Test
    void deletesEveryVersionOfAFile() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-delete-file.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                var first = repository.insert(modelWithCompartment(mapper, fileId, "v1"));
                repository.insert(modelWithCompartment(mapper, fileId, "v2"));

                var deleted = queryRepository.deleteByFileId(fileId).orElseThrow();

                assertEquals(2, deleted.documents());
                assertTrue(deleted.nodes() >= 4, "both documents and their entities must be gone");
                assertEquals(List.of(), queryRepository.findVersions(fileId));
                assertEquals(0L, ownedNodeCount(session, first.uid()));
                assertEquals(0L, orphanCount(session, "PUBLICATION"));
                assertEquals(0L, orphanCount(session, "PERSON"));
                assertTrue(queryRepository.deleteByFileId(fileId).isEmpty());
            } finally {
                deleteFile(session, fileId);
            }
        }
    }

    /**
     * QRY-8 schema half: the initializer creates the seven B-tree indexes the exact-copy equality
     * predicates run against, in the database the container serves.
     */
    @Test
    void schemaDeclaresTheSevenExactMatchIndexes() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();

            var indexNames = session.executeRead(tx -> tx.run("SHOW INDEXES YIELD name RETURN name")
                    .list(record -> record.get("name").asString()));

            assertTrue(
                    indexNames.containsAll(List.of(
                            "model_source_uri_exact",
                            "model_model_id_exact",
                            "resource_uri_exact",
                            "person_family_name_exact",
                            "person_given_name_exact",
                            "publication_pub_id_exact",
                            "publication_year_exact")),
                    () -> "exact-match indexes missing from " + indexNames);
        }
    }

    /**
     * QRY-8 query half: a URI clause is an equality on the lowercased {@code sourceUriExact} copy, so
     * the whole URI finds the one document and the path fragment finds nothing, while the keyword
     * query still matches the model through the full-text index.
     */
    @Test
    void exactUriMatchesOnlyTheWholeValue() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-exact-uri.sbml";
        var sourceUri = "https://example.org/models/BIOMD0000000001";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                var stored = repository.insert(mapper.toGraph(new ParsedModel(
                        new ModelReference(fileId, URI.create(sourceUri), ModelType.SBML, "v1"),
                        "BIOMD0000000001",
                        "Containers exact URI",
                        Map.of())));

                var exactHits = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, sourceUri)),
                        10));
                assertEquals(1, exactHits.size(), "the whole URI must match exactly one model");
                assertEquals(stored.uid(), exactHits.getFirst().uid());
                assertNotNull(exactHits.getFirst().uid());
                assertEquals(URI.create(sourceUri), exactHits.getFirst().documentUri());

                assertEquals(
                        List.of(),
                        queryRepository.queryModels(new ModelQueryRequest(
                                null,
                                List.of(new QueryClause(QueryField.URI, "BIOMD0000000001")),
                                10)),
                        "a path fragment is not the URI and must not match");

                var keywordHits = queryRepository.queryModels(new ModelQueryRequest("BIOMD0000000001", List.of(), 10));
                assertEquals(1, keywordHits.size(), "the keyword query must still find the model");
                assertEquals(stored.uid(), keywordHits.getFirst().uid());
            } finally {
                deleteFile(session, fileId);
            }
        }
    }

    /**
     * QRY-8 on the metadata-only path: {@code POST /api/v1/models} writes a {@code :Document:Model}
     * node through {@link Neo4jModelRepository#insert(ModelReference)}; it carries
     * {@code sourceUriExact}, so it is found by the exact URI even though no full-text document
     * backs it.
     */
    @Test
    void metadataOnlyModelIsFoundByItsExactUri() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-metadata-only.sbml";
        var sourceUri = "https://example.org/models/metadata-only-%s".formatted(UUID.randomUUID());
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var queryRepository = new Neo4jModelRepository(driver, settings.database());

            try {
                var stored = queryRepository.insert(
                        new ModelReference(fileId, URI.create(sourceUri), ModelType.SBML));

                var hits = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, sourceUri)),
                        10));

                assertEquals(1, hits.size(), "the metadata-only node must be found by its exact URI");
                assertNotNull(hits.getFirst().uid(), "a metadata-only hit still reports a non-null uid");
                assertEquals(stored.uid(), hits.getFirst().uid());
            } finally {
                deleteFile(session, fileId);
            }
        }
    }

    /**
     * QRY-4: the score floor cuts the low-score tail of a full-text annotation query. The annotation
     * node is created directly because the floor and the exact-only skip are what this test proves;
     * {@code uriExact} is stamped by hand only so the third assertion exercises the same row.
     */
    @Test
    void scoreFloorCutsTheLowScoreTail() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var resourceUri = "https://example.org/res/1";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var resourceQueryRepository = new Neo4jResourceQueryRepository(driver, settings.database());

            try {
                session.executeWrite(tx -> tx.run("""
                        CREATE (:RESOURCE {uri: $uri, uriExact: $uri, resourceText: 'kinetic annotation'})
                        """, Map.of("uri", resourceUri)).consume());

                var hits = resourceQueryRepository.queryAnnotations(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.RESOURCE_TEXT, "kinetic")),
                        10,
                        null));
                assertFalse(hits.isEmpty(), "the annotation must be found without a floor");

                assertEquals(
                        List.of(),
                        resourceQueryRepository.queryAnnotations(new ModelQueryRequest(
                                null,
                                List.of(new QueryClause(QueryField.RESOURCE_TEXT, "kinetic")),
                                10,
                                1000.0)),
                        "a floor above every full-text score must cut every hit");

                // The exact-only path skips the database entirely for such a floor; the RESOURCE
                // node above is the proof that it was not queried.
                assertEquals(
                        List.of(),
                        resourceQueryRepository.queryAnnotations(new ModelQueryRequest(
                                null,
                                List.of(new QueryClause(QueryField.RESOURCE_URI, resourceUri)),
                                10,
                                1000.0)),
                        "an exact-only hit scores 1.0, so a floor above it must cut it");
            } finally {
                session.executeWrite(tx -> tx.run(
                        "MATCH (n:RESOURCE {uri: $uri}) DETACH DELETE n",
                        Map.of("uri", resourceUri)).consume());
            }
        }
    }

    /**
     * QRY-1: three versions of one fileId plus one model with another fileId, all matching the same
     * keyword. The three versions differ only in how often the keyword appears in the indexed
     * {@code compartment} aggregate, so their full-text scores must differ. A grouped request returns
     * one row per fileId: the shared fileId carries three versions, ordered by their own score, each
     * with a distinct version label, and the other fileId carries exactly one version.
     */
    @Test
    void groupedQueryReturnsOneRowPerFile() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-grouped-versions.sbml";
        var otherFileId = "containers-it-grouped-other.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();
            var keyword = "quinquepartite";

            try {
                insertThreeVersions(repository, mapper, fileId);
                repository.insert(groupedVersion(mapper, otherFileId, "v1", 1));

                var grouped = queryRepository.queryModels(
                        new ModelQueryRequest(keyword, List.of(), 10, null, true));

                assertEquals(2, grouped.size(), "one row per fileId, not one row per hit");

                var shared = grouped.stream()
                        .filter(result -> fileId.equals(result.filename()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no group for " + fileId));
                assertEquals(3, shared.versions().size(), "the shared fileId must carry all three versions");
                assertEquals(
                        List.of("v3", "v2", "v1"),
                        shared.versions().stream().map(version -> version.versionId()).toList(),
                        "more occurrences must score higher, so v3 leads");
                assertEquals(3, shared.versions().stream().map(ModelVersionHit::uid).distinct().count(),
                        "every version must have its own uid");
                shared.versions().forEach(version -> assertTrue(
                        version.score() > 0.0, () -> "a full-text hit must score above zero: " + version));
                assertDescending(shared.versions());

                var other = grouped.stream()
                        .filter(result -> otherFileId.equals(result.filename()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no group for " + otherFileId));
                assertEquals(1, other.versions().size(), "the single-version file must carry one version");
            } finally {
                deleteFile(session, fileId);
                deleteFile(session, otherFileId);
            }
        }
    }

    /**
     * QRY-1: {@code top} counts groups, not hits. With {@code top = 1} the grouped query returns one
     * row, and that row still carries all three matching versions of the winning fileId.
     */
    @Test
    void groupedQueryTopCountsGroups() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-grouped-top.sbml";
        var otherFileId = "containers-it-grouped-top-other.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();
            var keyword = "quinquepartite";

            try {
                insertThreeVersions(repository, mapper, fileId);
                repository.insert(groupedVersion(mapper, otherFileId, "v1", 1));

                var grouped = queryRepository.queryModels(
                        new ModelQueryRequest(keyword, List.of(), 1, null, true));

                assertEquals(1, grouped.size(), "top counts groups, so one group must be returned");
                var winner = grouped.getFirst();
                assertEquals(fileId, winner.filename());
                assertEquals(3, winner.versions().size(), "a group is not cut down to a single version");
                assertEquals(
                        List.of("v3", "v2", "v1"),
                        winner.versions().stream().map(version -> version.versionId()).toList());
            } finally {
                deleteFile(session, fileId);
                deleteFile(session, otherFileId);
            }
        }
    }

    /**
     * QRY-1: the group's flat fields are the representative version's — the one with the highest
     * score in {@code versions()}. A row that mixed fields from different versions is the regression.
     */
    @Test
    void groupedQueryTakesTheRepresentativeFromTheBestVersion() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-grouped-representative.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();
            var keyword = "quinquepartite";

            try {
                insertThreeVersions(repository, mapper, fileId);

                var grouped = queryRepository.queryModels(
                        new ModelQueryRequest(keyword, List.of(), 10, null, true));

                assertEquals(1, grouped.size(), "one fileId means one group");
                var group = grouped.getFirst();
                assertEquals(3, group.versions().size(), "the group must carry all three versions");

                var best = group.versions().getFirst();
                assertEquals("v3", best.versionId(), "three occurrences must outscore two and one");
                assertEquals(best.uid(), group.uid(), "the representative row must be the best version");
                assertEquals(
                        "model_" + fileId + "_v3",
                        group.modelId(),
                        "the group's modelId must come from the best-scoring version");
                assertDescending(group.versions());
            } finally {
                deleteFile(session, fileId);
            }
        }
    }

    /**
     * QRY-1: without the flag the query stays flat — one row per hit, no versions list.
     */
    @Test
    void ungroupedQueryIsUnchanged() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-ungrouped.sbml";
        var otherFileId = "containers-it-ungrouped-other.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();
            var keyword = "quinquepartite";

            try {
                insertThreeVersions(repository, mapper, fileId);
                repository.insert(groupedVersion(mapper, otherFileId, "v1", 1));

                var flat = queryRepository.queryModels(new ModelQueryRequest(keyword, List.of(), 10));

                assertEquals(4, flat.size(), "the ungrouped query still returns one row per hit");
                flat.forEach(result -> assertTrue(
                        result.versions().isEmpty(), "an ungrouped row carries no versions list"));
                assertEquals(
                        3,
                        flat.stream().filter(result -> fileId.equals(result.filename())).count(),
                        "the three versions appear as three flat rows");
            } finally {
                deleteFile(session, fileId);
                deleteFile(session, otherFileId);
            }
        }
    }

    /**
     * QRY-2: two versions of one fileId both match the keyword, but only one of them carries a whole
     * stored document while the other was stored truncated. A flat query must report each row's own
     * version label, the original size of the document (never the truncated size), and the truncation
     * flag that belongs to that version; the request-derived fields are the same on every row.
     */
    @Test
    void richResultReportsVersionAndDocumentFacts() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-rich-flat.sbml";
        var keyword = "quinquepartite";
        var bytes = "<sbml>Grüße</sbml>".getBytes(StandardCharsets.UTF_8);
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                repository.insert(groupedVersion(mapper, fileId, "v1", 1)
                        .withSourceDocument(SourceDocument.from(bytes, 1024)));
                repository.insert(groupedVersion(mapper, fileId, "v2", 2)
                        .withSourceDocument(SourceDocument.from(bytes, 6)));

                var flat = queryRepository.queryModels(new ModelQueryRequest(keyword, List.of(), 10));

                assertEquals(2, flat.size(), "the two versions appear as two flat rows");
                var whole = flat.stream()
                        .filter(result -> "v1".equals(result.versionId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no flat row for v1 in " + flat));
                var cut = flat.stream()
                        .filter(result -> "v2".equals(result.versionId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no flat row for v2 in " + flat));

                flat.forEach(result -> assertEquals(fileId, result.filename()));
                flat.forEach(result -> assertEquals("modelFulltextV6", result.indexSource()));
                flat.forEach(result -> assertEquals(List.of(QueryField.KEYWORD), result.matchedFields()));

                assertTrue(whole.documentStored(), "v1 stored its document");
                assertEquals((long) bytes.length, whole.documentBytes(),
                        "the whole document reports its original size");
                assertFalse(whole.documentTruncated(), "v1 fits under its ceiling");

                assertTrue(cut.documentStored(), "v2 stored a truncated copy, which still counts as stored");
                assertEquals((long) bytes.length, cut.documentBytes(),
                        "a truncated document must report the original size, not the stored size");
                assertTrue(cut.documentTruncated(), "v2 was cut at its six-byte ceiling");
            } finally {
                deleteFile(session, fileId);
            }
        }
    }

    /**
     * QRY-2: the same two stored documents queried with {@code groupByVersion}. The group's flat
     * fields are the representative's — v2 repeats the keyword twice, so it must win — and that
     * representative's document facts must travel with it rather than come from the cut copy.
     */
    @Test
    void richGroupedResultReportsTheRepresentativeFacts() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-rich-grouped.sbml";
        var keyword = "quinquepartite";
        var bytes = "<sbml>Grüße</sbml>".getBytes(StandardCharsets.UTF_8);
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                repository.insert(groupedVersion(mapper, fileId, "v1", 1)
                        .withSourceDocument(SourceDocument.from(bytes, 6)));
                repository.insert(groupedVersion(mapper, fileId, "v2", 2)
                        .withSourceDocument(SourceDocument.from(bytes, 1024)));

                var grouped = queryRepository.queryModels(
                        new ModelQueryRequest(keyword, List.of(), 10, null, true));

                assertEquals(1, grouped.size(), "one fileId means one group");
                var group = grouped.getFirst();
                assertEquals(2, group.versions().size(), "the group must carry both versions");

                var representative = group.versions().getFirst();
                assertEquals("v2", representative.versionId(), "two occurrences must outscore one");
                assertEquals(representative.versionId(), group.versionId(),
                        "the group must report its representative's version label");
                assertTrue(group.documentStored(), "the representative stored its document");
                assertEquals((long) bytes.length, group.documentBytes(),
                        "the representative documents its original size");
                assertFalse(group.documentTruncated(),
                        "the representative was stored whole; the cut v1 must not leak into the group");
                assertEquals("modelFulltextV6", group.indexSource());
                assertEquals(List.of(QueryField.KEYWORD), group.matchedFields());
            } finally {
                deleteFile(session, fileId);
            }
        }
    }

    private static final String CML3_KEYWORD = "zephirokardic";

    /**
     * CML-3: a CellML model whose external PMR cmeta carries a keyword is found by that keyword through
     * the model query, and the hit names the {@code modelFulltextV6} generation. The sample is indexed
     * through the real indexing service with the file resolver switched on (external cmeta stays
     * opt-in); the sibling {@code <model>@@cmeta} file lives in the test work directory, so neither
     * {@code samples/} nor the module resources are written. The model's own name deliberately carries
     * no part of the keyword: only a keyword indexed as {@code Model.keywords} can produce the hit.
     */
    @Test
    void findsACellmlModelByTheKeywordOfItsExternalCmeta() throws Exception {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-cellml-keywords.cellml";
        var workDirectory = Path.of("target", "cml3-keywords-it", UUID.randomUUID().toString());
        var model = workDirectory.resolve(fileId);
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                Files.createDirectories(workDirectory);
                Files.writeString(model, minimalCellml("Containers IT external cmeta keywords"), StandardCharsets.UTF_8);
                Files.writeString(
                        workDirectory.resolve(fileId + "@@cmeta"),
                        externalCmeta(CML3_KEYWORD),
                        StandardCharsets.UTF_8);

                var service = new CellmlModelIndexingService(
                        new ModelSourceDiscovery(),
                        new UriModelSourceContentLoader(),
                        new CellmlModelParser(),
                        mapper,
                        repository);
                var indexed =
                        service.indexUris(List.of(model.toAbsolutePath().toUri()), new RemotePmrCellmlExternalCmetaResolver());
                assertEquals(1, indexed.size(), "the one CellML source must be indexed");
                session.executeWrite(tx -> tx.run("CALL db.awaitIndexes(30)").consume());

                assertEquals(
                        CML3_KEYWORD,
                        session.executeRead(tx -> tx.run("""
                                        MATCH (d:Document {fileId: $fileId})-[:HAS_MODEL]->(m:Model)
                                        RETURN m.keywords AS keywords
                                        """, Map.of("fileId", fileId))
                                .single()
                                .get("keywords")
                                .asString()),
                        "the mapper must aggregate the cmeta keywords onto the model node");

                var byKeyword = queryRepository.queryModels(new ModelQueryRequest(CML3_KEYWORD, List.of(), 10));
                assertEquals(
                        List.of(fileId),
                        byKeyword.stream().map(ModelSearchResult::filename).toList(),
                        "only the keyword fixture may match: " + byKeyword);
                var hit = byKeyword.getFirst();
                assertEquals("modelFulltextV6", hit.indexSource(), "a full-text hit names the V5 generation");
                assertEquals(List.of(QueryField.KEYWORD), hit.matchedFields());

                var byClause = queryRepository.queryModels(new ModelQueryRequest(
                        null, List.of(new QueryClause(QueryField.KEYWORD, CML3_KEYWORD)), 10));
                assertEquals(
                        List.of(fileId),
                        byClause.stream().map(ModelSearchResult::filename).toList(),
                        "a KEYWORD clause must find the model too: " + byClause);
                assertEquals("modelFulltextV6", byClause.getFirst().indexSource());

                var unknown = queryRepository.queryModels(new ModelQueryRequest("nomatchxyzcml3", List.of(), 10));
                assertEquals(List.of(), unknown, "the keyword must be the only reason the model is found");
            } finally {
                deleteFile(session, fileId);
                if (Files.exists(workDirectory)) {
                    try (var entries = Files.walk(workDirectory)) {
                        entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                    }
                }
            }
        }
    }

    /** A CellML 1.0 model with one component and no metadata of its own, so only the cmeta can match. */
    private static String minimalCellml(String name) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.0#" name="%s">
                  <component name="cell"/>
                </model>
                """.formatted(name);
    }

    /** The PMR {@code @@cmeta} document the file resolver reads from beside the model. */
    private static String externalCmeta(String keyword) {
        return """
                {"keywords": [["%s"]], "title": "Containers IT external cmeta", "model_author": "Containers IT"}
                """.formatted(keyword);
    }

    /**
     * QRY-2: an exact-only URI clause never touches the full-text index, so the hit names the
     * {@code exact} index source and carries the clause field — not the keyword.
     */
    @Test
    void exactOnlyResultReportsTheExactIndexSource() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-rich-exact.sbml";
        var sourceUri = "https://example.org/models/rich-exact-" + UUID.randomUUID();
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                var stored = repository.insert(mapper.toGraph(new ParsedModel(
                        new ModelReference(fileId, URI.create(sourceUri), ModelType.SBML, "v1"),
                        "model_" + fileId,
                        "Containers rich exact",
                        Map.of())));

                var hits = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, sourceUri)),
                        10));

                assertEquals(1, hits.size(), "the whole URI must match exactly one model");
                var hit = hits.getFirst();
                assertEquals(stored.uid(), hit.uid());
                assertEquals("exact", hit.indexSource(), "an exact-only hit names the exact index");
                assertEquals(List.of(QueryField.URI), hit.matchedFields(), "no keyword means no KEYWORD field");
            } finally {
                deleteFile(session, fileId);
            }
        }
    }

    /**
     * QRY-7: the kind filter separates the model types on the real query, and the metadata-only insert
     * carries the same kind label as a parsed document. Every fixture name contains the keyword, so the
     * unfiltered query has to return all three kinds.
     */
    @Test
    void kindFilterSeparatesTheModelTypes() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sbml = "containers-it-kinds-sbml.xml";
        var cellml = "containers-it-kinds-cellml.xml";
        var sedml = "containers-it-kinds-sedml.sedml";
        var metadataOnly = "containers-it-kinds-metadata.xml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                repository.insert(kindFixture(mapper, sbml, ModelType.SBML, "SBML"));
                repository.insert(kindFixture(mapper, cellml, ModelType.CELLML, "CellML"));
                repository.insert(kindFixture(mapper, sedml, ModelType.SEDML, "SED-ML"));
                queryRepository.insert(new ModelReference(
                        metadataOnly,
                        URI.create("urn:masymos:containers-it:kinds:" + metadataOnly),
                        ModelType.SBML,
                        "v1"));

                assertEquals(
                        3,
                        queryRepository.queryModels(new ModelQueryRequest(Q7_KEYWORD, List.of(), 10)).size(),
                        "an unfiltered request keeps every kind");
                assertEquals(
                        List.of(sbml),
                        modelFileIds(queryRepository.queryModels(
                                new ModelQueryRequest(Q7_KEYWORD, List.of(), 10, null, false, Set.of(ModelType.SBML)))),
                        "the SBML filter keeps only the SBML model");
                assertEquals(
                        List.of(cellml),
                        modelFileIds(queryRepository.queryModels(new ModelQueryRequest(
                                Q7_KEYWORD, List.of(), 10, null, false, Set.of(ModelType.CELLML)))),
                        "the CellML filter keeps only the CellML model");

                // The metadata-only row is reachable by its kind only because the insert stamps the label.
                var metadataRows = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, "urn:masymos:containers-it:kinds:" + metadataOnly)),
                        10,
                        null,
                        false,
                        Set.of(ModelType.SBML)));
                assertEquals(1, metadataRows.size(), "a metadata-only insert must match its kind filter");
                assertEquals(metadataOnly, metadataRows.getFirst().filename());
            } finally {
                deleteFile(session, sbml);
                deleteFile(session, cellml);
                deleteFile(session, sedml);
                deleteFile(session, metadataOnly);
            }
        }
    }

    /**
     * QRY-7/QRY-9: the SED-ML endpoint returns descriptions (not models) with the sources they declare,
     * and the archive endpoint returns the archive with its document count. A keyword that matches
     * nothing returns an empty list from both.
     */
    @Test
    void sedmlAndArchiveQueriesReturnTheirOwnRows() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sbml = "containers-it-typed-sbml.xml";
        var cellml = "containers-it-typed-cellml.xml";
        var sedml = "containers-it-typed-sedml.sedml";
        var archive = "containers-it-typed.omex";
        var archiveUri = "https://example.org/bundles/" + archive;
        var referenceSource = "urn:miriam:biomodels.db:BIOMD0000000001";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                repository.insert(kindFixture(mapper, sbml, ModelType.SBML, "SBML"));
                repository.insert(kindFixture(mapper, cellml, ModelType.CELLML, "CellML"));
                repository.insert(descriptionFixture(mapper, sedml, referenceSource));
                repository.linkArchive(
                        URI.create(archiveUri),
                        List.of(
                                URI.create("urn:masymos:containers-it:kinds:" + sbml),
                                URI.create("urn:masymos:containers-it:kinds:" + cellml)));

                var sedmlRepository = new Neo4jSedmlQueryRepository(driver, settings.database());
                var descriptions = sedmlRepository.querySedml(new ModelQueryRequest(Q7_KEYWORD, List.of(), 10));
                assertEquals(1, descriptions.size(), "only the description is a SED-ML result: " + descriptions);
                assertEquals(sedml, descriptions.getFirst().fileId());
                assertTrue(
                        descriptions.getFirst().referencedModelUris().contains(URI.create(referenceSource)),
                        "an unresolvable source is reported as declared: "
                                + descriptions.getFirst().referencedModelUris());
                assertEquals(
                        List.of(),
                        sedmlRepository.querySedml(new ModelQueryRequest("nomatchxyz", List.of(), 10)));

                var archiveRepository = new Neo4jArchiveQueryRepository(driver, settings.database());
                var archives = archiveRepository.queryArchives(new ModelQueryRequest(
                        null, List.of(new QueryClause(QueryField.ARCHIVE_NAME, archive)), 10));
                assertEquals(1, archives.size(), "the archive must be found by its name: " + archives);
                assertEquals(archive, archives.getFirst().fileId());
                assertEquals(2L, archives.getFirst().documentCount(), "both entry documents belong to the archive");
                assertEquals(
                        List.of(),
                        archiveRepository.queryArchives(
                                new ModelQueryRequest("nomatchxyz", List.of(), 10)));
            } finally {
                deleteFile(session, sbml);
                deleteFile(session, cellml);
                deleteFile(session, sedml);
                session.executeWrite(tx -> tx.run(
                                "MATCH (a:CombineArchive {sourceUri: $uri}) DETACH DELETE a", Map.of("uri", archiveUri))
                        .consume());
            }
        }
    }

    /**
     * ANNO-1a: the maintenance run over the term stage against a real server. One SBML model carries
     * one ontology-annotated resource whose term was pre-seeded into the cache, so a {@code providers}
     * run must answer it from the cache — the provider is a counter that must stay at zero — write
     * {@code resolutionMode=providers}, {@code resolvedVia=bioportal} and its {@code termKey}, and a
     * second run with {@code dropExistingIndex} in {@code off} must re-index the same resource from
     * the cache alone.
     */
    @Test
    void resolverDrivenRunRecordsProvenanceAndReprocessesFromTheCache() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-resolver.sbml";
        var resourceUri = "urn:miriam:obo.go:GO_0008150";
        var termKey = new TermKey("go", "0008150");
        var termText = "signal transduction cached term text";
        var providerCalls = new AtomicInteger();
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                repository.insert(mapper.toGraph(new ParsedModel(
                        new ModelReference(
                                fileId,
                                URI.create("urn:masymos:containers-it:resolver"),
                                ModelType.SBML,
                                "v1"),
                        "containers_it_resolver",
                        "Containers IT resolver",
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of(new ParsedAnnotation(
                                "model",
                                Map.of(),
                                List.of(new ParsedAnnotationResource(resourceUri, "isDescribedBy")),
                                List.of(),
                                List.of())),
                        List.of())));

                var cache = new Neo4jAnnotationTermCache(driver, settings.database());
                cache.saveAll(List.of(AnnotationTermCache.CachedTerm.of(
                        TermDefinition.resolved(termKey, termText, TermDefinition.Source.BIOPORTAL),
                        Instant.now())));

                var catalogue = AnnotationPrefixCatalogue.standard();
                var resolver = new AnnotationTermResolver(
                        catalogue,
                        List.of(new CountingProvider(providerCalls, TermDefinition.Source.BIOPORTAL)),
                        cache,
                        Clock.systemUTC(),
                        AnnotationTermResolver.DEFAULT_TTL,
                        url -> AnnotationFetchResult.failure(url, "the run must not fetch a page"),
                        new AnnotationTextExtractor(),
                        AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
                var service = new Neo4jAnnotationIndexService(driver, settings.database(), resolver, catalogue);

                var providersRun = service.rebuild(new AnnotationIndexingOptions(
                        false, 0, true, 4, ResolverMode.PROVIDERS, false));

                assertEquals(0, providerCalls.get(), "a cached term must not reach the provider");
                assertEquals(1L, providersRun.indexedResources(), "the one resource must be indexed");
                assertEquals(ResolverMode.PROVIDERS, providersRun.terms().mode());
                assertEquals(1L, providersRun.terms().cacheHits(), "the term came from the cache");

                var provenance = session.executeRead(tx -> tx.run("""
                        MATCH (resource:RESOURCE {uri: $uri})
                        RETURN resource.resourceText AS resourceText,
                               resource.resolutionMode AS resolutionMode,
                               resource.resolvedVia AS resolvedVia,
                               resource.termKey AS termKey
                        """, Map.of("uri", resourceUri)).single());
                assertEquals(termText, provenance.get("resourceText").asString());
                assertEquals("providers", provenance.get("resolutionMode").asString());
                assertEquals("bioportal", provenance.get("resolvedVia").asString());
                assertEquals(termKey.wire(), provenance.get("termKey").asString());

                // Inside the TTL, so bioportal-sourced text is fresh: off re-indexes it from the cache.
                var offRun = service.rebuild(new AnnotationIndexingOptions(
                        true, 0, true, 4, ResolverMode.OFF, false));

                assertEquals(0, providerCalls.get(), "off mode asks no provider");
                assertEquals(1L, offRun.indexedResources(), "the dropped resource is re-indexed from the cache");
                assertEquals("off", provenanceOf(session, resourceUri, "resolutionMode"));
                assertEquals("bioportal", provenanceOf(session, resourceUri, "resolvedVia"));
                assertEquals(termKey.wire(), provenanceOf(session, resourceUri, "termKey"));
                assertEquals(termText, provenanceOf(session, resourceUri, "resourceText"));
            } finally {
                deleteFile(session, fileId);
                session.executeWrite(tx -> tx.run("""
                        MATCH (n:ONTOLOGY_TERM) WHERE n.termKey = $key OR n.key = $key DETACH DELETE n
                        """, Map.of("key", termKey.wire())).consume());
            }
        }
    }

    /** One stored property of one resource, for the provenance assertions. */
    private static String provenanceOf(org.neo4j.driver.Session session, String resourceUri, String property) {
        var value = session.executeRead(tx -> tx.run(
                        "MATCH (resource:RESOURCE {uri: $uri}) RETURN resource[$property] AS value",
                        Map.of("uri", resourceUri, "property", property))
                .single()
                .get("value"));
        return value == null || value.isNull() ? null : value.asString();
    }

    /** A provider that must never be asked in this run; it counts every attempt instead. */
    private record CountingProvider(AtomicInteger calls, TermDefinition.Source source) implements AnnotationTermProvider {

        @Override
        public boolean supports(TermKey key) {
            return true;
        }

        @Override
        public Map<TermKey, TermDefinition> resolveAll(java.util.Collection<TermKey> keys) {
            calls.incrementAndGet();
            var answered = new java.util.LinkedHashMap<TermKey, TermDefinition>();
            for (var key : keys) {
                answered.put(key, TermDefinition.resolved(key, "provider text for " + key.wire(), source));
            }
            return answered;
        }
    }

    /** The keyword every QRY-7 fixture carries in its name. */
    private static final String Q7_KEYWORD = "kindscopefixture";

    /** One model of the requested kind, with the keyword in its name so the model index finds it. */
    /**
     * SEDML-5: the link pass joins on identity, not on a URI suffix. Two SBML models whose URIs share
     * their trailing 15 characters are seeded next to a SED-ML reference that names only one of them
     * (a MIRIAM URN against an identifiers.org annotation), plus an exact archive-entry URI. The
     * look-alike must stay unlinked; the other two must link.
     */
    @Test
    void linksModelReferencesByIdentityNotByUriSuffix() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session()) {
            driver.verifyConnectivity();
            try {
                session.executeWrite(tx -> tx.run("""
                        CREATE (intended:SBML_MODEL {uid: 'it-intended', it: 'sedml-5'})
                        CREATE (lookalike:SBML_MODEL {uid: 'it-lookalike', it: 'sedml-5'})
                        CREATE (entry:SBML_MODEL {uid: 'it-entry', it: 'sedml-5'})
                        CREATE (intended)-[:HAS_ANNOTATION]->(:ANNOTATION {it: 'sedml-5'})
                                -[:is]->(:RESOURCE {it: 'sedml-5',
                                        uri: 'http://identifiers.org/biomodels.db/BIOMD0000000005'})
                        CREATE (lookalike)-[:HAS_ANNOTATION]->(:ANNOTATION {it: 'sedml-5'})
                                -[:is]->(:RESOURCE {it: 'sedml-5',
                                        uri: 'http://mirror.example/BIOMD0000000005'})
                        CREATE (entry)-[:HAS_ANNOTATION]->(:ANNOTATION {it: 'sedml-5'})
                                -[:is]->(:RESOURCE {it: 'sedml-5',
                                        uri: 'file:/data/x.omex!/models/a.sbml'})
                        CREATE (:SEDML_MODELREFERENCE {it: 'sedml-5',
                                modelSource: 'urn:miriam:biomodels.db:BIOMD0000000005'})
                        CREATE (:SEDML_MODELREFERENCE {it: 'sedml-5',
                                modelSource: 'file:/data/x.omex!/models/a.sbml'})
                        """).consume());

                var linked = session.executeWrite(tx -> tx.run(
                                SedmlLinkingCypher.linkModelReferences().cypher())
                        .single().get("relationshipCount").asLong());
                assertEquals(2L, linked, "one MIRIAM pair and one exact entry URI");

                var uids = session.executeRead(tx -> tx.run("""
                        MATCH (:SEDML_MODELREFERENCE)-[:REFERENCES_SIMULATION_MODEL]->(model:SBML_MODEL)
                        RETURN collect(DISTINCT model.uid) AS uids
                        """).single().get("uids").asList(value -> value.asString()));
                assertEquals(Set.of("it-intended", "it-entry"), Set.copyOf(uids));
                assertFalse(uids.contains("it-lookalike"),
                        "a model whose URI only shares the 15-character tail must not link: " + uids);
            } finally {
                session.executeWrite(tx -> tx.run(
                        "MATCH (n {it: 'sedml-5'}) DETACH DELETE n").consume());
            }
        }
    }

    /**
     * CML-1: two models that use the same unit produce one node, and the second model's centimetre
     * declaration is a definition edge to that node rather than a second metre.
     */
    @Test
    void twoModelsShareOneMetreNode() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var metreFile = "containers-it-units-metre.cellml";
        var centiFile = "containers-it-units-centi.cellml";

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                repository.insert(unitFixture(mapper, metreFile, metre()));
                repository.insert(unitFixture(mapper, centiFile, centimetre(), metre()));

                var nodes = session.executeRead(tx -> tx.run(
                                "MATCH (u:UNIT {key: 'base:metre'}) RETURN count(u) AS count")
                        .single()
                        .get("count")
                        .asLong());
                assertEquals(1L, nodes, "one metre node for both models");

                var users = session.executeRead(tx -> tx.run(
                                """
                                MATCH (m:Model)-[:USES_UNIT]->(u0:UNIT)-[:COMPOSED_OF*0..]->(u:UNIT {key: 'base:metre'})
                                RETURN count(DISTINCT m) AS count
                                """)
                        .single()
                        .get("count")
                        .asLong());
                assertEquals(2L, users, "both models reach the metre");

                var composition = session.executeRead(tx -> tx.run(
                                """
                                MATCH (:UNIT {key: 'unit:0.01*metre^1'})-[:COMPOSED_OF]->(:UNIT {key: 'base:metre'})
                                RETURN count(*) AS count
                                """)
                        .single()
                        .get("count")
                        .asLong());
                assertEquals(1L, composition, "centimetre is defined from the shared metre");
            } finally {
                deleteFile(session, metreFile);
                deleteFile(session, centiFile);
            }
        }
    }

    /**
     * CML-1: the shared unit survives while anything still names it, and goes with the last user.
     * The unit is the nanomolar definition, which only this test creates, so a node another test
     * left behind cannot answer the assertion. It is also composed (mole, litre, metre), which is
     * the closure the purge has to keep while a user remains.
     */
    @Test
    void deletingOneOfTwoModelsKeepsTheSharedUnit() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var first = "containers-it-units-first.cellml";
        var second = "containers-it-units-second.cellml";
        var nanomolar = nanomolar();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                repository.insert(unitFixture(mapper, first, nanomolar, metre(), litre(), mole()));
                repository.insert(unitFixture(mapper, second, nanomolar, metre(), litre(), mole()));

                queryRepository.deleteByFileId(first);
                assertEquals(1L, unitCount(session, nanomolar.key()), "the survivor still uses it");

                queryRepository.deleteByFileId(second);
                var diagnostics = session.executeRead(tx -> tx.run(
                                """
                                MATCH (u:UNIT {key: $key})
                                OPTIONAL MATCH (m:Model)-[:USES_UNIT]->(u)
                                OPTIONAL MATCH (e)-[:HAS_UNIT]->(u)
                                RETURN count(DISTINCT u) AS units,
                                       count(DISTINCT m) AS uses,
                                       count(DISTINCT e) AS entities
                                """,
                                Map.of("key", nanomolar.key()))
                        .single()
                        .toString());
                assertEquals(
                        0L,
                        unitCount(session, nanomolar.key()),
                        "the last user took the unit with it; graph: " + diagnostics);
            } finally {
                deleteFile(session, first);
                deleteFile(session, second);
            }
        }
    }

    /**
     * GAP-01-004: the only HAS_UNIT evidence in this suite was an {@code OPTIONAL MATCH} whose counts
     * were stringified into a failure message, so a write that emitted no HAS_UNIT edge at all stayed
     * green - which is how the mapper's unit-key mismatch (`GAP-01-001`) could go unnoticed. This
     * asserts the edge against the fixture's own declaration: one variable carries one declared unit,
     * the edge ends at the node that unit is keyed by, and every step of the match is mandatory.
     */
    @Test
    void aUnitWriteYieldsHasUnitEdges() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var file = "containers-it-units-has-unit.cellml";
        var nanomolar = nanomolar();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());

            try {
                repository.insert(unitFixture(new ParsedModelGraphMapper(), file, nanomolar, metre()));

                var row = session.executeRead(tx -> tx.run(
                                """
                                MATCH (d:Document {fileId: $fileId})-[:HAS_MODEL]->(m:Model)
                                MATCH (m)<-[:BELONGS_TO]-(v)-[r:HAS_UNIT]->(u:UNIT)
                                RETURN count(r) AS edges,
                                       collect(DISTINCT v.units) AS declaredUnits,
                                       collect(DISTINCT u.key) AS unitKeys,
                                       collect(DISTINCT r.declaredName) AS declaredNames
                                """,
                                Map.of("fileId", file))
                        .single());

                assertEquals(
                        1L,
                        row.get("edges").asLong(),
                        "the fixture declares one unit-carrying variable; a graph without the edge answers 0 here: " + row);
                assertEquals(
                        List.of(nanomolar.display()),
                        row.get("declaredUnits").asList(value -> value.asString()),
                        "the edge starts at the variable entity the fixture declared: " + row);
                assertEquals(
                        List.of(nanomolar.key()),
                        row.get("unitKeys").asList(value -> value.asString()),
                        "the edge ends at the UNIT node the declared unit is keyed by: " + row);
                assertEquals(
                        List.of(nanomolar.display()),
                        row.get("declaredNames").asList(value -> value.asString()),
                        "the edge carries the name the document declared: " + row);
            } finally {
                deleteFile(session, file);
            }
        }
    }

    /**
     * FBC-3: the one half of the fbc slice a unit test cannot prove - that a gene product's label and
     * id reach the full-text index and answer a model search (decision 52). The write half is asserted
     * in the same run, because a hit without the node would mean the aggregate came from somewhere else.
     *
     * <p>Measured red before the index carried the field: the graph answers both nodes and all three
     * edges, and the keyword query answers nothing - so the failure names the missing index entry and
     * not the parser.</p>
     */
    @Test
    void aFbcGeneProductIsWrittenAndFoundByItsLabel() throws Exception {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-fbc-gene-products.sbml";
        var workDirectory = Path.of("target", "fbc-it", UUID.randomUUID().toString());
        var model = workDirectory.resolve(fileId);
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var queryRepository = new Neo4jModelRepository(driver, settings.database());

            try {
                Files.createDirectories(workDirectory);
                Files.writeString(model, fbcSbml(), StandardCharsets.UTF_8);

                var service = new SbmlModelIndexingService(
                        new ModelSourceDiscovery(),
                        new UriModelSourceContentLoader(),
                        new SbmlModelParser(),
                        new ParsedModelGraphMapper(),
                        new Neo4jExtractedModelGraphRepository(driver, settings.database()));
                assertEquals(1, service.indexUris(List.of(model.toAbsolutePath().toUri())).size(),
                        "the one SBML source must be indexed");
                session.executeWrite(tx -> tx.run("CALL db.awaitIndexes(30)").consume());

                var written = session.executeRead(tx -> tx.run("""
                                MATCH (d:Document {fileId: $fileId})-[:HAS_MODEL]->(m:Model)
                                OPTIONAL MATCH (m)-[:HAS_GENE_PRODUCT]->(g:SBML_GENE_PRODUCT)
                                OPTIONAL MATCH (:SBML_REACTION)-[a:ASSOCIATED_WITH]->(g)
                                RETURN count(DISTINCT g) AS geneProducts,
                                       collect(DISTINCT g.id) AS ids,
                                       count(DISTINCT a) AS associations
                                """, Map.of("fileId", fileId))
                        .single());
                assertEquals(2L, written.get("geneProducts").asLong(), "both gene products are nodes: " + written);
                assertEquals(
                        List.of("G1", "G2"),
                        written.get("ids").asList(value -> value.asString()).stream().sorted().toList(),
                        "the nodes carry the ids the document declares: " + written);
                assertEquals(
                        3L,
                        written.get("associations").asLong(),
                        "R1 -> G1, R2 -> {G1, G2}, with R2's repeated G1 collapsed: " + written);

                var byLabel = queryRepository.queryModels(new ModelQueryRequest("g1", List.of(), 10));
                assertEquals(
                        List.of(fileId),
                        byLabel.stream().map(ModelSearchResult::filename).toList(),
                        "the label must answer a model search: " + byLabel);
                assertEquals(List.of(QueryField.KEYWORD), byLabel.getFirst().matchedFields());
                assertEquals("modelFulltextV6", byLabel.getFirst().indexSource(), "a text hit names the V6 generation");

                var byId = queryRepository.queryModels(new ModelQueryRequest("G2", List.of(), 10));
                assertEquals(
                        List.of(fileId),
                        byId.stream().map(ModelSearchResult::filename).toList(),
                        "the id sits in the same aggregate as the label: " + byId);

                var unknown = queryRepository.queryModels(new ModelQueryRequest("nomatchxyzfbc", List.of(), 10));
                assertEquals(List.of(), unknown, "the gene product must be the only reason the model is found");
            } finally {
                deleteFile(session, fileId);
                if (Files.exists(workDirectory)) {
                    try (var entries = Files.walk(workDirectory)) {
                        entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                    }
                }
            }
        }
    }

    /**
     * A Level 3 version 1 document with two gene products and two reactions: R1 names G1 directly, R2
     * nests an {@code or} inside an {@code and} and names G1 twice, so one edge per referenced gene
     * product and one for the duplicate is what a correct write produces. Inline rather than borrowed
     * from {@code masymos-core}'s test resources, which are not on this module's test classpath.
     */
    private static String fbcSbml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version1/core"
                      xmlns:fbc="http://www.sbml.org/sbml/level3/version1/fbc/version2"
                      level="3" version="1" fbc:required="false">
                  <model id="fbc_containers_it" name="FBC containers IT" fbc:strict="true">
                    <listOfCompartments>
                      <compartment id="c" size="1" constant="true"/>
                    </listOfCompartments>
                    <listOfSpecies>
                      <species id="s1" compartment="c" hasOnlySubstanceUnits="false"
                               boundaryCondition="false" constant="false"/>
                    </listOfSpecies>
                    <fbc:listOfGeneProducts>
                      <fbc:geneProduct fbc:id="G1" fbc:label="g1"/>
                      <fbc:geneProduct fbc:id="G2" fbc:label="g2"/>
                    </fbc:listOfGeneProducts>
                    <listOfReactions>
                      <reaction id="R1" reversible="false">
                        <listOfReactants>
                          <speciesReference species="s1" stoichiometry="1" constant="true"/>
                        </listOfReactants>
                        <fbc:geneProductAssociation>
                          <fbc:geneProductRef fbc:geneProduct="G1"/>
                        </fbc:geneProductAssociation>
                      </reaction>
                      <reaction id="R2" reversible="false">
                        <listOfReactants>
                          <speciesReference species="s1" stoichiometry="1" constant="true"/>
                        </listOfReactants>
                        <fbc:geneProductAssociation>
                          <fbc:and>
                            <fbc:geneProductRef fbc:geneProduct="G1"/>
                            <fbc:or>
                              <fbc:geneProductRef fbc:geneProduct="G1"/>
                              <fbc:geneProductRef fbc:geneProduct="G2"/>
                            </fbc:or>
                          </fbc:and>
                        </fbc:geneProductAssociation>
                      </reaction>
                    </listOfReactions>
                  </model>
                </sbml>
                """;
    }

    private static long unitCount(Session session, String key) {
        return session.executeRead(tx -> tx.run(
                        "MATCH (u:UNIT {key: $key}) RETURN count(u) AS count", Map.of("key", key))
                .single()
                .get("count")
                .asLong());
    }

    /**
     * CML-1: {@code metre} matches centimetre, entries combine with AND, and an unknown unit is
     * refused rather than answered with an empty list.
     */
    @Test
    void filterByMetreMatchesCentimetre() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var centiFile = "containers-it-units-filter-centi.cellml";
        var secondFile = "containers-it-units-filter-second.cellml";

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                var centiUri = "urn:masymos:containers-it:units:" + centiFile;
                repository.insert(unitFixture(mapper, centiFile, centimetre(), metre(), second()));
                repository.insert(unitFixture(mapper, secondFile, second()));

                var hits = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, centiUri)),
                        10,
                        null,
                        false,
                        null,
                        List.of("metre")));
                assertEquals(1, hits.size(), "the centimetre model matches a metre filter");

                var both = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, centiUri)),
                        10,
                        null,
                        false,
                        null,
                        List.of("metre", "second")));
                assertEquals(1, both.size(), "the entries AND, so the seconds-only model is excluded");

                var misses = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, "urn:masymos:containers-it:units:" + secondFile)),
                        10,
                        null,
                        false,
                        null,
                        List.of("metre")));
                assertEquals(0, misses.size(), "a model in seconds does not match a metre filter");

                assertThrows(
                        IllegalArgumentException.class,
                        () -> queryRepository.queryModels(new ModelQueryRequest(
                                null,
                                List.of(new QueryClause(QueryField.URI, centiUri)),
                                10,
                                null,
                                false,
                                null,
                                List.of("furlong_per_fortnight"))));
            } finally {
                deleteFile(session, centiFile);
                deleteFile(session, secondFile);
            }
        }
    }

    /**
     * QRY-12: two entries combine with AND, proven where it can actually fail. The {@code both}
     * request in {@code filterByMetreMatchesCentimetre} is narrowed by a {@code URI} clause to the one
     * model that satisfies both entries, so an OR-flattened predicate would answer it identically -
     * that test proves the parameter shape and the depth-0/composition rule, not the AND. Here the
     * model satisfies only the second entry, so a flattened predicate returns a row and the contract
     * fails loudly.
     */
    @Test
    void twoUnitEntriesRequireBothWhereOnlyOneIsSatisfied() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var secondFile = "containers-it-units-and-second.cellml";

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                var secondUri = "urn:masymos:containers-it:units:" + secondFile;
                repository.insert(unitFixture(mapper, secondFile, second()));

                var onlySecond = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, secondUri)),
                        10,
                        null,
                        false,
                        null,
                        List.of("second")));
                assertEquals(1, onlySecond.size(), "the positive control: the model is filterable by second");

                var both = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, secondUri)),
                        10,
                        null,
                        false,
                        null,
                        List.of("metre", "second")));
                assertEquals(0, both.size(), "the metre entry is unsatisfied, so the entries AND");
            } finally {
                deleteFile(session, secondFile);
            }
        }
    }

    /**
     * Review focus 4, the other half: a model that declares its own base unit is not matched by a
     * filter for a different unit. {@code CellmlUnitExtractionTest.anEmptyUnitsElementIsADeclaredBase}
     * proves the declaration is kept; nothing yet proved that keeping it does not make the model match
     * every unit filter. The positive control in the same test proves the model is indexed and
     * filterable under its own name, so the zero rows cannot come from a document that never arrived.
     */
    @Test
    void aModelDeclaringItsOwnBaseUnitDoesNotMatchEveryUnitFilter() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var declaredFile = "containers-it-units-declared-base.cellml";
        var metreFile = "containers-it-units-declared-metre.cellml";

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                var declaredUri = "urn:masymos:containers-it:units:" + declaredFile;
                repository.insert(unitFixture(mapper, declaredFile, declaredBase()));
                repository.insert(unitFixture(mapper, metreFile, metre()));

                var selfHits = queryRepository.queryModels(new ModelQueryRequest(
                        null, List.of(new QueryClause(QueryField.URI, declaredUri)), 10, null, false, null, List.of("cell")));
                assertEquals(1, selfHits.size(), "the declared base is filterable under its own name");

                var misses = queryRepository.queryModels(new ModelQueryRequest(
                        null, List.of(new QueryClause(QueryField.URI, declaredUri)), 10, null, false, null, List.of("metre")));
                assertEquals(
                        0,
                        misses.size(),
                        "declaring a base unit the graph does not know does not make the model use metre");

                var metres = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(
                                QueryField.URI, "urn:masymos:containers-it:units:" + metreFile)),
                        10,
                        null,
                        false,
                        null,
                        List.of("metre")));
                assertEquals(1, metres.size(), "the metre model is still found, so the filter itself works");
            } finally {
                deleteFile(session, declaredFile);
                deleteFile(session, metreFile);
            }
        }
    }

    /**
     * CML-1: an imported {@code <units>} declaration is a unit of the importing model, so a filter
     * for it finds that model. Before {@code CellmlImportResolver.resolveUnits} the unit stayed
     * {@code kind=unresolved}, reachable from no {@code USES_UNIT}, and this query returned nothing -
     * the model imported its unit and looked like it used none.
     */
    @Test
    void aModelUsingAnImportedUnitMatchesAUnitFilter() throws Exception {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-units-import-host.cellml";
        var workDirectory = Path.of("target", "cml1-units-import-it", UUID.randomUUID().toString());
        var host = workDirectory.resolve(fileId);
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                Files.createDirectories(workDirectory);
                Files.writeString(
                        workDirectory.resolve("units-lib.cellml"),
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <model xmlns="http://www.cellml.org/cellml/1.0#" name="units-lib">
                          <units name="per_ms">
                            <unit units="second" prefix="-3" exponent="-1"/>
                          </units>
                        </model>
                        """,
                        StandardCharsets.UTF_8);
                Files.writeString(
                        host,
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <model xmlns="http://www.cellml.org/cellml/1.0#"
                               xmlns:xlink="http://www.w3.org/1999/xlink" name="host">
                          <import xlink:href="units-lib.cellml">
                            <units units_ref="per_ms"/>
                          </import>
                          <component name="transporter">
                            <variable name="k" units="per_ms"/>
                          </component>
                        </model>
                        """,
                        StandardCharsets.UTF_8);

                var service = new CellmlModelIndexingService(
                        new ModelSourceDiscovery(),
                        new UriModelSourceContentLoader(),
                        new CellmlModelParser(
                                CellmlExternalCmetaResolver.NONE, new FileCellmlImportResolver(workDirectory)),
                        mapper,
                        repository);
                var indexed = service.indexUris(
                        List.of(host.toAbsolutePath().toUri()), new RemotePmrCellmlExternalCmetaResolver());
                assertEquals(1, indexed.size(), "the importing model must be indexed");

                var hits = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, host.toAbsolutePath().toUri().toString())),
                        10,
                        null,
                        false,
                        null,
                        List.of("per_ms")));
                assertEquals(
                        List.of(fileId),
                        hits.stream().map(ModelSearchResult::filename).toList(),
                        "the imported unit must be filterable: " + hits);

                var misses = queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, host.toAbsolutePath().toUri().toString())),
                        10,
                        null,
                        false,
                        null,
                        List.of("metre")));
                assertEquals(0, misses.size(), "the import is per millisecond, not a metre: " + misses);
            } finally {
                deleteFile(session, fileId);
                if (Files.exists(workDirectory)) {
                    try (var entries = Files.walk(workDirectory)) {
                        entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                    }
                }
            }
        }
    }

    /**
     * STO-6: a document indexed before CML-1 has no unit layer, and the backfill repairs it from the
     * source document the graph already stores - not by re-indexing, which would append a version
     * (decision 36). The test inserts exactly that shape: the parsed graph with its unit nodes and
     * unit edges stripped, but with the captured source document attached.
     *
     * <p>Four claims, in the order a caller would make them: a dry run changes no answer; the applied
     * run makes the unit filter find the model under a name only the document knows; a second run
     * writes nothing; and the version chain is untouched throughout.
     */
    @Test
    void backfillsTheUnitLayerOfADocumentIndexedBeforeCml1() throws Exception {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-backfill-units.cellml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.0#" name="backfill">
                  <units name="centimetre">
                    <unit units="metre" prefix="-2"/>
                  </units>
                  <component name="cell">
                    <variable name="width" units="centimetre"/>
                  </component>
                </model>
                """;

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var backfill = new Neo4jUnitBackfill(driver, settings.database());
            var reference = new ModelReference(
                    fileId, URI.create("urn:masymos:containers-it:backfill"), ModelType.CELLML, "v1");
            var sourceUri = reference.sourceUri().toString();

            try {
                var bytes = xml.getBytes(StandardCharsets.UTF_8);
                ParsedModel parsed;
                try (var stream = new java.io.ByteArrayInputStream(bytes)) {
                    parsed = new CellmlModelParser().parse(new org.masymos.core.extract.ModelSource(reference), stream);
                }
                var graph = new ParsedModelGraphMapper().toGraph(parsed);
                var unitKeys = graph.nodes().stream()
                        .filter(node -> node.labels().contains("UNIT"))
                        .map(ExtractedGraphNode::key)
                        .collect(Collectors.toSet());
                var withoutUnits = new ExtractedModelGraph(
                                graph.reference(),
                                graph.nodes().stream()
                                        .filter(node -> !unitKeys.contains(node.key()))
                                        .toList(),
                                graph.relationships().stream()
                                        .filter(relationship -> !unitKeys.contains(relationship.startKey())
                                                && !unitKeys.contains(relationship.endKey())
                                                && !"USES_UNIT".equals(relationship.type()))
                                        .toList())
                        .withSourceDocument(SourceDocument.from(bytes, SourceDocument.DEFAULT_MAX_BYTES));
                repository.insert(withoutUnits);

                assertEquals(
                        List.of(),
                        unitsQuery(queryRepository, sourceUri, "metre"),
                        "before the backfill the document has no unit to match");

                // Unbounded on purpose: this IT asserts over the whole store, and a bounded run would
                // sample its first 500 rows. The bound itself is pinned by UnitBackfillCypherTest.
                var dryRun = backfill.run(false, UnitBackfill.UNBOUNDED);
                assertEquals(
                        List.of(),
                        unitsQuery(queryRepository, sourceUri, "metre"),
                        "a dry run must not write: " + dryRun);
                assertTrue(dryRun.documentsBackfilled() >= 1, "the document is one of the ones it would fix");
                assertTrue(dryRun.writes() > 0, "a dry run still reports what it would create: " + dryRun);

                var applied = backfill.run(true, UnitBackfill.UNBOUNDED);
                assertTrue(applied.applied(), "the report names the mode it ran in");
                assertEquals(
                        List.of(fileId),
                        unitsQuery(queryRepository, sourceUri, "metre"),
                        "the backfill makes the metre filter reach the document: " + applied);
                assertEquals(
                        List.of(fileId),
                        unitsQuery(queryRepository, sourceUri, "centimetre"),
                        "a name only the document knows resolves once its units are in the graph");

                var second = backfill.run(true, UnitBackfill.UNBOUNDED);
                assertEquals(0, second.writes(), "idempotent: the second run creates nothing: " + second);
                assertEquals(0, second.documentsBackfilled(), "and it has nothing left to backfill: " + second);
                assertTrue(second.documentsAlreadyComplete() >= 1, "it reports the document as complete: " + second);

                var versions = session.executeRead(tx -> tx.run(
                                "MATCH (d:Document {fileId: $fileId}) RETURN count(d) AS count",
                                Map.of("fileId", fileId))
                        .single()
                        .get("count")
                        .asLong());
                assertEquals(1L, versions, "a backfill must not append a version");
            } finally {
                deleteFile(session, fileId);
            }
        }
    }

    /**
     * {@code C04-2}: a bounded run reports the position it stopped at, and the next run continues after
     * it. The property an operator needs is that a loop over bounded requests finishes the corpus -
     * before the cursor, every bounded run selected the same first page, so the documents beyond it
     * were unreachable however often the request was repeated, and the "already complete costs one
     * count" note in the report was true of a page the run never got past.
     *
     * <p>The loop is the documented usage, so the test runs it rather than asserting on one page.
     */
    @Test
    void aBoundedBackfillRunIsResumedByTheCursorItReports() throws Exception {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var fileIds = List.of(
                "containers-it-cursor-units-1.cellml",
                "containers-it-cursor-units-2.cellml",
                "containers-it-cursor-units-3.cellml");

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var backfill = new Neo4jUnitBackfill(driver, settings.database());
            try {
                for (var fileId : fileIds) {
                    insertWithoutUnitLayer(repository, fileId);
                }

                String cursor = null;
                var limit = 2;
                var pages = 0;
                for (; pages < fileIds.size() + 1 && (pages == 0 || cursor != null); pages++) {
                    cursor = backfill.run(true, limit, cursor).nextCursor();
                }

                assertNull(cursor, "a caller's loop terminates: the last page reports no cursor");
                assertEquals(
                        (fileIds.size() + limit - 1) / limit,
                        pages,
                        "three documents at two per page is two pages, and both are needed");
                for (var fileId : fileIds) {
                    var withUnits = session.executeRead(tx -> tx
                            .run(
                                    """
                                    MATCH (d:Document {fileId: $fileId})-[:HAS_MODEL]->(:Model)-[:USES_UNIT]->(:UNIT)
                                    RETURN count(*) AS count
                                    """,
                                    Map.of("fileId", fileId))
                            .single()
                            .get("count")
                            .asLong());
                    assertTrue(withUnits > 0, "a bounded sweep must reach " + fileId + ", not just the first page");
                }
            } finally {
                for (var fileId : fileIds) {
                    deleteFile(session, fileId);
                }
            }
        }
    }

    /**
     * One CellML document of the pre-CML-1 shape: its unit nodes and unit edges are stripped from the
     * parsed graph, so only the backfill can supply them, while the source document stays attached.
     */
    private static void insertWithoutUnitLayer(Neo4jExtractedModelGraphRepository repository, String fileId)
            throws IOException {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.0#" name="cursor">
                  <units name="centimetre">
                    <unit units="metre" prefix="-2"/>
                  </units>
                  <component name="cell">
                    <variable name="width" units="centimetre"/>
                  </component>
                </model>
                """;
        var reference = new ModelReference(
                fileId, URI.create("urn:masymos:containers-it:" + fileId), ModelType.CELLML, "v1");
        var bytes = xml.getBytes(StandardCharsets.UTF_8);
        ParsedModel parsed;
        try (var stream = new java.io.ByteArrayInputStream(bytes)) {
            parsed = new CellmlModelParser().parse(new org.masymos.core.extract.ModelSource(reference), stream);
        }
        var graph = new ParsedModelGraphMapper().toGraph(parsed);
        var unitKeys = graph.nodes().stream()
                .filter(node -> node.labels().contains("UNIT"))
                .map(ExtractedGraphNode::key)
                .collect(Collectors.toSet());
        repository.insert(new ExtractedModelGraph(
                        graph.reference(),
                        graph.nodes().stream()
                                .filter(node -> !unitKeys.contains(node.key()))
                                .toList(),
                        graph.relationships().stream()
                                .filter(relationship -> !unitKeys.contains(relationship.startKey())
                                        && !unitKeys.contains(relationship.endKey())
                                        && !"USES_UNIT".equals(relationship.type()))
                                .toList())
                .withSourceDocument(SourceDocument.from(bytes, SourceDocument.DEFAULT_MAX_BYTES)));
    }

    private static List<String> unitsQuery(
            Neo4jModelRepository queryRepository, String sourceUri, String unit) {
        return queryRepository.queryModels(new ModelQueryRequest(
                        null,
                        List.of(new QueryClause(QueryField.URI, sourceUri)),
                        10,
                        null,
                        false,
                        null,
                        List.of(unit)))
                .stream()
                .map(ModelSearchResult::filename)
                .toList();
    }

    private static CanonicalUnit declaredBase() {
        return new CanonicalUnit("declared-base:cell", "cell", List.of("cell"), "base", "cell", "1", null, List.of());
    }

    private static CanonicalUnit nanomolar() {
        return new CanonicalUnit(
                "unit:0.000001*metre^-3*mole^1",
                "nanomolar",
                List.of("nanomolar"),
                "derived",
                "metre^-3*mole^1",
                "0.000001",
                null,
                List.of(new UnitComponent("base:mole", "1", "0.000000001"), new UnitComponent("unit:0.001*metre^3", "-1", "1")));
    }

    private static CanonicalUnit metre() {
        return new CanonicalUnit("base:metre", "metre", List.of("metre"), "base", "metre^1", "1", null, List.of());
    }

    private static CanonicalUnit second() {
        return new CanonicalUnit("base:second", "second", List.of("second"), "base", "second^1", "1", null, List.of());
    }

    private static CanonicalUnit litre() {
        return new CanonicalUnit(
                "unit:0.001*metre^3",
                "litre",
                List.of("litre"),
                "derived",
                "metre^3",
                "0.001",
                null,
                List.of(new UnitComponent("base:metre", "3", "1")));
    }

    private static CanonicalUnit mole() {
        return new CanonicalUnit("base:mole", "mole", List.of("mole"), "base", "mole^1", "1", null, List.of());
    }

    private static CanonicalUnit centimetre() {
        return new CanonicalUnit(
                "unit:0.01*metre^1",
                "centimetre",
                List.of("centimetre"),
                "derived",
                "metre^1",
                "0.01",
                null,
                List.of(new UnitComponent("base:metre", "1", "0.01")));
    }

    private static ExtractedModelGraph unitFixture(
            ParsedModelGraphMapper mapper, String fileId, CanonicalUnit... units) {
        var entities = new ArrayList<ParsedModelEntity>(UnitEntityBuilder.entities(List.of(units)));
        entities.add(new ParsedModelEntity(
                ParsedModelEntity.EntityType.VARIABLE, "main/v", "v", Map.of("units", units[0].display())));
        var relationships = new ArrayList<ParsedEntityRelationship>(UnitEntityBuilder.composition(List.of(units)));
        relationships.add(new ParsedEntityRelationship(
                "VARIABLE:main/v",
                "HAS_UNIT",
                "UNIT:" + units[0].key(),
                Map.of("declaredName", units[0].display())));
        return mapper.toGraph(new ParsedModel(
                new ModelReference(
                        fileId,
                        URI.create("urn:masymos:containers-it:units:" + fileId),
                        ModelType.CELLML,
                        "v1"),
                "model_" + fileId,
                fileId,
                Map.of(),
                entities,
                List.of(),
                List.of(),
                List.of(),
                relationships));
    }

    private static ExtractedModelGraph kindFixture(            ParsedModelGraphMapper mapper, String fileId, ModelType type, String label) {
        return mapper.toGraph(new ParsedModel(
                new ModelReference(
                        fileId,
                        URI.create("urn:masymos:containers-it:kinds:" + fileId),
                        type,
                        "v1"),
                "model_" + fileId,
                Q7_KEYWORD + " " + label,
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    }

    /** A SED-ML description whose single model reference declares an unresolvable source. */
    private static ExtractedModelGraph descriptionFixture(
            ParsedModelGraphMapper mapper, String fileId, String modelSource) {
        return mapper.toGraph(new ParsedModel(
                new ModelReference(
                        fileId,
                        URI.create("urn:masymos:containers-it:kinds:" + fileId),
                        ModelType.SEDML,
                        "v1"),
                "model_" + fileId,
                Q7_KEYWORD + " description",
                Map.of(),
                List.of(new ParsedModelEntity(
                        ParsedModelEntity.EntityType.SEDML_MODELREFERENCE,
                        "model1",
                        "model1",
                        Map.of("modelSource", modelSource))),
                List.of(),
                List.of(),
                List.of()));
    }

    private static List<String> modelFileIds(List<ModelSearchResult> results) {
        return results.stream().map(ModelSearchResult::filename).toList();
    }

    /** v1, v2 and v3 of one file with 1, 2 and 3 keyword occurrences, so v3 must score highest. */
    private static void insertThreeVersions(
            Neo4jExtractedModelGraphRepository repository, ParsedModelGraphMapper mapper, String fileId) {
        repository.insert(groupedVersion(mapper, fileId, "v1", 1));
        repository.insert(groupedVersion(mapper, fileId, "v2", 2));
        repository.insert(groupedVersion(mapper, fileId, "v3", 3));
    }

    /**
     * One version of a grouped-query fixture. The keyword is the compartment name, repeated
     * {@code repetitions} times, so a version's full-text score rises with the repetition count
     * while the shape of the document stays the same. {@code modelId} names the version, so the
     * representative-row test can tell which version the group reported.
     */
    private static org.masymos.core.extract.ExtractedModelGraph groupedVersion(
            ParsedModelGraphMapper mapper, String fileId, String version, int repetitions) {
        var name = "quinquepartite ".repeat(repetitions).trim();
        return mapper.toGraph(new ParsedModel(
                new ModelReference(
                        fileId,
                        URI.create("urn:masymos:containers-it:grouped:" + fileId + ":" + version),
                        ModelType.SBML,
                        version),
                "model_" + fileId + "_" + version,
                "Containers IT " + fileId,
                Map.of(),
                List.of(new ParsedModelEntity(
                        ParsedModelEntity.EntityType.COMPARTMENT,
                        "quinquepartite",
                        name,
                        Map.of("id", "quinquepartite", "name", name))),
                List.of(),
                List.of(),
                List.of()));
    }

    /** Fails unless every score is at least as high as the one after it. */
    private static void assertDescending(List<ModelVersionHit> versions) {
        for (var index = 1; index < versions.size(); index++) {
            assertTrue(
                    versions.get(index - 1).score() >= versions.get(index).score(),
                    () -> "versions must be ordered by score descending, but they are: " + versions);
        }
    }

    private static org.masymos.core.extract.ExtractedModelGraph modelWithCompartment(
            ParsedModelGraphMapper mapper, String fileId, String version) {
        return mapper.toGraph(new ParsedModel(
                new ModelReference(fileId, URI.create("urn:masymos:containers-it:" + fileId + ":" + version),
                        ModelType.SBML, version),
                "model_" + fileId,
                "Containers IT " + fileId,
                Map.of(),
                List.of(new ParsedModelEntity(
                        ParsedModelEntity.EntityType.COMPARTMENT,
                        "cell",
                        "Cell",
                        Map.of("id", "cell", "name", "Cell"))),
                List.of(),
                List.of(),
                List.of(new org.masymos.core.extract.ParsedPublication(
                        "pub-" + version,
                        "Title " + version,
                        "Journal",
                        null,
                        null,
                        "2014",
                        List.of(new org.masymos.core.extract.ParsedPerson(
                                "Jane", "Doe", "jj@example.org", "Uni"))))));
    }

    private static long ownedNodeCount(org.neo4j.driver.Session session, String uid) {
        return session.executeRead(tx -> tx.run(
                        "MATCH (n) WHERE n.ownerUid = $uid RETURN count(n) AS count",
                        Map.of("uid", uid))
                .single()
                .get("count")
                .asLong());
    }

    /**
     * Every node any document write owned. Used after a failed write, whose own uid was minted and
     * discarded inside the transaction and so cannot be named by the test.
     */
    private static long ownedNodeCount(Session session) {
        return session.executeRead(tx -> tx.run(
                        "MATCH (n) WHERE n.ownerUid IS NOT NULL RETURN count(n) AS count")
                .single()
                .get("count")
                .asLong());
    }

    /** The legacy counter's value, or {@code 0L} while no document has ever been written. */
    private static long uniqueIdCounter(Session session) {
        return session.executeRead(tx -> {
            var result = tx.run("MATCH (id:GlobalUniqueId) RETURN id.count AS count");
            return result.hasNext() ? result.next().get("count").asLong() : 0L;
        });
    }

    /** The owner uid of every stored document of one file, read before the file is deleted. */
    private static List<String> documentOwnerUids(Session session, String fileId) {
        return session.executeRead(tx -> tx.run(
                        "MATCH (d:Document {fileId: $fileId}) RETURN DISTINCT d.ownerUid AS uid",
                        Map.of("fileId", fileId))
                .list(record -> record.get("uid").asString()));
    }

    private static long documentCount(org.neo4j.driver.Session session, String fileId, String versionId) {
        return session.executeRead(tx -> tx.run("""
                        MATCH (d:Document {fileId: $fileId, versionId: $versionId})
                        RETURN count(d) AS count
                        """, Map.of("fileId", fileId, "versionId", versionId))
                .single()
                .get("count")
                .asLong());
    }

    private static long successorCount(org.neo4j.driver.Session session, String fileId) {
        return session.executeRead(tx -> tx.run("""
                        MATCH (:Document {fileId: $fileId})-[:HAS_SUCCESSOR]->(:Document {fileId: $fileId})
                        RETURN count(*) AS count
                        """, Map.of("fileId", fileId))
                .single()
                .get("count")
                .asLong());
    }

    private static long orphanCount(org.neo4j.driver.Session session, String label) {
        return session.executeRead(tx -> tx.run(
                        "MATCH (n:" + label + ") WHERE NOT (n)--() RETURN count(n) AS count")
                .single()
                .get("count")
                .asLong());
    }

    private static void deleteFile(org.neo4j.driver.Session session, String fileId) {
        session.executeWrite(tx -> tx.run("""
                MATCH (d:Document {fileId: $fileId})
                OPTIONAL MATCH (d)-[*0..]-(connected)
                WITH DISTINCT connected
                DETACH DELETE connected
                """, Map.of("fileId", fileId)).consume());
    }

    /**
     * QRY-3 composition: the real {@link RankFusionQueryService} over the real model, resource and row
     * repositories. Two versions of one file and one version of another file all match the keyword;
     * an annotation on the first file matches it as free text too. The fused list must hold one row
     * per model file (two, not three), the annotated file must carry model <em>and</em> annotation
     * provenance, the fused scores must be on the strategy's own {@code (0, 1]} scale and descending,
     * and the flat model query must still answer with one row per version.
     */
    @Test
    void aggregatedQueryFusesRankersAndReportsProvenance() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-fusion.sbml";
        var otherFileId = "containers-it-fusion-other.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var modelRepository = new Neo4jModelRepository(driver, settings.database());
            var resourceRepository = new Neo4jResourceQueryRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();
            var service = new RankFusionQueryService(
                    modelRepository,
                    resourceRepository,
                    new Neo4jModelRowLookup(driver, settings.database()),
                    List.of(new BordaCombMnzStrategy(), new BestRankStrategy()));
            var keyword = FUSION_KEYWORD;

            try {
                insertFusionCorpus(repository, mapper, fileId, otherFileId);

                // D9: an annotation is a fact about the model file, so both versions resolve to it.
                // D8 then lets the scores pick the representative, and measured 2026-11-14 that is v1:
                // the annotation's resource text and URI are indexed on v1's model node too, which
                // outweighs v2's extra compartment repetition — that field saturates (tf 1 -> 0.0726,
                // tf 3 -> 0.0814) while every further matching field adds about 0.13 (QRY-11).
                var annotatedVersionUri = documentSourceUri(session, fileId, "v1");
                var otherVersionUri = documentSourceUri(session, fileId, "v2");
                assertAnnotationHitBeforeFusion(
                        resourceRepository, keyword, Set.of(annotatedVersionUri, otherVersionUri));

                var hits = service.query(
                        new AggregatedQuery(keyword, List.of(), 10, null, BordaCombMnzStrategy.ID, FusionWeights.none()));

                assertEquals(2, hits.size(), "two files, although three versions exist: " + describe(hits));
                assertEquals(
                        2,
                        hits.stream().map(hit -> hit.row().fileId()).distinct().count(),
                        "every hit must stand for its own file: " + describe(hits));
                assertTrue(
                        hits.stream().allMatch(hit -> fileId.equals(hit.row().fileId())
                                || otherFileId.equals(hit.row().fileId())),
                        "only the seeded files may be fused: " + describe(hits));

                var annotated = hits.stream()
                        .filter(hit -> fileId.equals(hit.row().fileId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no fused row for " + fileId + " in " + describe(hits)));
                // The rule behind the expectation, so the assertion cannot pass on fixture luck: the
                // representative is the version the model ranker places first for this file.
                var bestScoredVersion = modelRepository
                        .queryModels(new ModelQueryRequest(keyword, List.of(), 10))
                        .stream()
                        .filter(result -> fileId.equals(result.filename()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the flat model query lost " + fileId));
                assertEquals(
                        bestScoredVersion.uid(),
                        annotated.row().uid(),
                        () -> "the representative is the file's best-scoring version; hits=" + describe(hits));
                assertEquals(
                        annotatedVersionUri,
                        annotated.row().documentUri(),
                        () -> "that version's document URI is reported; hits=" + describe(hits));
                assertEquals(
                        "v1",
                        annotated.row().versionId(),
                        () -> "the representative version is named, not implied; hits=" + describe(hits));
                assertEquals(
                        Set.of(Ranker.MODEL, Ranker.ANNOTATION),
                        annotated.sources().stream().map(FusionSource::ranker).collect(Collectors.toSet()),
                        "the annotation must raise the model it belongs to: " + annotated.sources());
                annotated.sources().forEach(source -> {
                    assertTrue(source.rank() >= 1, () -> "a rank is 1-based: " + source);
                    assertTrue(source.score() > 0.0, () -> "a full-text native score is positive: " + source);
                });

                hits.forEach(hit -> {
                    assertNotNull(hit.fusedScore(), () -> "bordaCombMnz always produces a score: " + hit);
                    assertTrue(
                            hit.fusedScore() > 0.0 && hit.fusedScore() <= 1.0,
                            () -> "the fused score lives in (0, 1]: " + hit);
                });
                for (var index = 1; index < hits.size(); index++) {
                    assertTrue(
                            hits.get(index - 1).fusedScore() >= hits.get(index).fusedScore(),
                            () -> "hits must be ordered by fused score descending: " + describe(hits));
                }

                var flat = modelRepository.queryModels(new ModelQueryRequest(keyword, List.of(), 10));
                assertEquals(
                        Map.of(fileId, 2L, otherFileId, 1L),
                        flat.stream()
                                .collect(Collectors.groupingBy(ModelSearchResult::filename, Collectors.counting())),
                        "the flat model query still returns one row per version: " + flat);
                flat.forEach(result -> assertTrue(
                        result.score() > 0.0, () -> "the per-entity score is unchanged and positive: " + result));
            } finally {
                deleteFile(session, fileId);
                deleteFile(session, otherFileId);
            }
        }
    }

    /**
     * QRY-3 order-only strategy over the same corpus: neither row carries a fused score, so the order
     * alone must express the ranking. The annotated file is returned by two rankers, the other by one,
     * so it leads, and two identical requests must return the identical order.
     */
    @Test
    void bestRankOrdersWithoutAScore() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-fusion-bestrank.sbml";
        var otherFileId = "containers-it-fusion-bestrank-other.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var modelRepository = new Neo4jModelRepository(driver, settings.database());
            var resourceRepository = new Neo4jResourceQueryRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();
            var service = new RankFusionQueryService(
                    modelRepository,
                    resourceRepository,
                    new Neo4jModelRowLookup(driver, settings.database()),
                    List.of(new BordaCombMnzStrategy(), new BestRankStrategy()));
            var keyword = FUSION_KEYWORD;
            var request =
                    new AggregatedQuery(keyword, List.of(), 10, null, BestRankStrategy.ID, FusionWeights.none());

            try {
                insertFusionCorpus(repository, mapper, fileId, otherFileId);

                var annotatedVersionUri = documentSourceUri(session, fileId, "v1");
                var representativeUri = documentSourceUri(session, fileId, "v2");
                assertAnnotationHitBeforeFusion(
                        resourceRepository, keyword, Set.of(annotatedVersionUri, representativeUri));

                var first = service.query(request);
                var second = service.query(request);

                assertEquals(2, first.size(), "two files, although three versions exist: " + describe(first));
                first.forEach(hit -> assertNull(hit.fusedScore(), () -> "an order-only strategy has no score: " + hit));
                assertEquals(
                        fileId,
                        first.getFirst().row().fileId(),
                        () -> "the file found by more rankers must lead: " + describe(first));
                assertEquals(
                        Set.of(Ranker.MODEL, Ranker.ANNOTATION),
                        first.getFirst().sources().stream().map(FusionSource::ranker).collect(Collectors.toSet()),
                        () -> "the representative carries both rankers' evidence: " + first.getFirst().sources());
                assertEquals(
                        fileIds(first), fileIds(second), "two identical requests must return the identical order");
            } finally {
                deleteFile(session, fileId);
                deleteFile(session, otherFileId);
            }
        }
    }

    /** The keyword every fusion fixture uses: in a compartment name and in the annotation text. */
    private static final String FUSION_KEYWORD = "fusion";

    /**
     * {@code REL-007} / {@code C03-2}: the row lookup's bound, measured against a real server. 2 500
     * matching rows - 500 document URIs with five versions each, the shape {@code T-016} named - must
     * answer 2 000 rows and say so; 2 000 matching rows - 400 URIs with five versions each - must
     * answer all of them and say nothing was cut. The two halves are one test on purpose: a marker
     * that is always {@code true} would pass the first half alone, and a marker that is always
     * {@code false} would pass the second.
     *
     * <p>The cut is also asserted to be the <em>stable</em> one the statement documents: {@code
     * ORDER BY uid} is the truncation order, so the lowest 2 000 uids are the ones kept and the tail
     * is the highest. Without that, "2 000 rows came back" would not say which ones.
     */
    @Test
    void answersAtMostTheRowBoundAndSaysSoWhenItCut() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var prefix = "containers-it-lookup-cut-";

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var lookup = new Neo4jModelRowLookup(driver, settings.database());

            try {
                seedLookupRows(session, prefix, 500, 5);

                var cut = lookup.byDocumentUris(lookupUris(0, 500));

                assertEquals(Neo4jModelRowLookup.MAX_LOOKUP_ROWS, cut.rows().size(),
                        "2 500 matching rows answer the bound, not all of them");
                assertTrue(cut.truncated(), "a cut must be reported: 2 500 rows matched, 2 000 were kept");
                assertEquals(expectedUids(prefix, 0, 400), cut.rows().stream().map(ModelRow::uid).toList(),
                        "ORDER BY uid is the truncation order, so the lowest uids are kept");

                var complete = lookup.byDocumentUris(lookupUris(0, 400));

                assertEquals(2000, complete.rows().size(), "exactly the bound is answered completely");
                assertFalse(complete.truncated(),
                        "2 000 matched rows are a complete answer, and the probe row never arrives");
            } finally {
                deleteByFileIdPrefix(session, prefix);
            }
        }
    }

    /**
     * {@code C03-3}, {@code C03-5}: {@code top} is served out of a per-ranker depth of {@code 5 * top}
     * clamped to 200, so a request above that cap cannot be filled however many files the store holds.
     * The row's residual asked where {@code top} stops being reachable on a store larger than one page,
     * and this is that measurement on a deliberately seeded store - the depth arithmetic itself is
     * pinned against a fake by {@code RankFusionQueryServiceTest#probesOneRowBeyondThePerRankerDepth},
     * so what is new here is the consequence: 300 matching files answer 200 rows, and since
     * {@code C03-5} the answer says the depth is why.
     *
     * <p>This is the boundary in both directions, which no always-true or always-false marker can
     * survive: {@code top = 150} reaches the same depth - the ranker is asked for 201 rows and holds
     * 300 - yet answers all 150 requested files, so it reports nothing; {@code top = 300} cannot be
     * filled, so every hit names the depth. With more than one version per file the same budget buys
     * fewer files, because the depth counts <em>versions</em>: {@code perRankerTop / versionsPerFile}
     * files at worst. That ratio is arithmetic over the two measured bounds rather than a third
     * measurement, because which 200 rows a tie in score returns is the database's business.
     */
    @Test
    void answersAtMostTheRankerDepthHoweverLargeTopIs() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var prefix = "containers-it-depth-";
        var keyword = "depthprobe";

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var service = new RankFusionQueryService(
                    new Neo4jModelRepository(driver, settings.database()),
                    new Neo4jResourceQueryRepository(driver, settings.database()),
                    new Neo4jModelRowLookup(driver, settings.database()),
                    List.of(new BordaCombMnzStrategy()));

            try {
                seedRankedFiles(session, prefix, keyword, 300);

                var belowTheCap = service.query(depthQuery(keyword, 150));

                assertEquals(150, belowTheCap.size(), () -> "a top below the depth is answered in full, was " + belowTheCap.size());
                assertTrue(
                        belowTheCap.stream().noneMatch(hit -> hit.limits().anyTruncated()),
                        () -> "and nothing claims a cut: the depth was reached, but every asked row was"
                                + " answered, so nothing was left out: "
                                + belowTheCap.stream().map(hit -> hit.limits()).toList());

                var aboveTheCap = service.query(depthQuery(keyword, 300));

                assertEquals(
                        200,
                        aboveTheCap.size(),
                        () -> "300 matching files must answer the 200-row depth, the documented "
                                + "MAX_PER_RANKER_TOP cap; this answered " + aboveTheCap.size());
                assertTrue(
                        aboveTheCap.stream().allMatch(hit -> hit.limits().rankerDepthTruncated()),
                        () -> "and every hit names the depth as the reason the answer is short: "
                                + aboveTheCap.stream().map(hit -> hit.limits()).toList());
                assertFalse(
                        aboveTheCap.stream().anyMatch(hit -> hit.limits().rowLookupTruncated()
                                || hit.limits().documentUrisTruncated()),
                        () -> "the depth is the only bound this request reached: "
                                + aboveTheCap.stream().map(hit -> hit.limits()).toList());
            } finally {
                deleteByFileIdPrefix(session, prefix);
            }
        }
    }

    /** One aggregated request over the model ranker alone, with {@code top} as given. */
    private static AggregatedQuery depthQuery(String keyword, int top) {
        return new AggregatedQuery(
                keyword,
                List.of(),
                top,
                null,
                BordaCombMnzStrategy.ID,
                FusionWeights.none(),
                Set.of(QueryEntity.MODEL),
                Set.of());
    }

    /**
     * Seeds {@code files} model files with one version each, every one matching {@code keyword} in the
     * full-text index, in one bounded statement: what this fixture measures is how many ranked hits a
     * request may see, and the write path is proven elsewhere in this class.
     */
    private static void seedRankedFiles(Session session, String prefix, String keyword, int files) {
        var rows = new ArrayList<Map<String, Object>>(files);
        for (var file = 0; file < files; file++) {
            var sourceUri = "urn:masymos:containers-it:depth:" + file;
            rows.add(Map.of(
                    "uid", String.format("%s%03d-v0", prefix, file),
                    "fileId", String.format("%s%03d.sbml", prefix, file),
                    "modelId", keyword + "-" + file,
                    "versionId", "v0",
                    "sourceUri", sourceUri,
                    "sourceUriExact", sourceUri));
        }
        session.executeWrite(tx -> tx.run("""
                UNWIND $rows AS row
                CREATE (document:Document {uid: row.uid, fileId: row.fileId, versionId: row.versionId,
                                           sourceUri: row.sourceUri, sourceUriExact: row.sourceUriExact})
                CREATE (model:Model {uid: row.uid, fileId: row.fileId, modelId: row.modelId,
                                     versionId: row.versionId, sourceUri: row.sourceUri,
                                     sourceUriExact: row.sourceUriExact})
                CREATE (document)-[:HAS_MODEL]->(model)
                """, Map.of("rows", rows)).consume());
    }

    /**
     * Seeds {@code uris * versions} model versions, each with its own {@code Document}, in one
     * bounded {@code UNWIND} statement rather than through the write path: 2 500 mapper insertions
     * would cost minutes, the write path is proven elsewhere in this class, and what this fixture is
     * for is the read bound. The property set is the one the lookup matches on - {@code uid},
     * {@code fileId}, {@code sourceUri} and the lowercase {@code sourceUriExact} copy that
     * {@code ParsedModelGraphCypher} stamps.
     */
    private static void seedLookupRows(Session session, String prefix, int uris, int versions) {
        var rows = new ArrayList<Map<String, Object>>(uris * versions);
        for (var uri = 0; uri < uris; uri++) {
            var sourceUri = lookupSourceUri(uri);
            for (var version = 0; version < versions; version++) {
                rows.add(Map.of(
                        "uid", String.format("%suri%03d-v%d", prefix, uri, version),
                        "fileId", String.format("%s%03d.sbml", prefix, uri),
                        "modelId", "lookup-" + uri,
                        "versionId", "v" + version,
                        "sourceUri", sourceUri,
                        "sourceUriExact", sourceUri));
            }
        }
        session.executeWrite(tx -> tx.run("""
                UNWIND $rows AS row
                CREATE (document:Document {uid: row.uid, fileId: row.fileId, versionId: row.versionId,
                                           sourceUri: row.sourceUri, sourceUriExact: row.sourceUriExact})
                CREATE (model:Model {uid: row.uid, fileId: row.fileId, modelId: row.modelId,
                                     versionId: row.versionId, sourceUri: row.sourceUri,
                                     sourceUriExact: row.sourceUriExact})
                CREATE (document)-[:HAS_MODEL]->(model)
                """, Map.of("rows", rows)).consume());
    }

    /** The {@code sourceUri} of the {@code index}-th seeded lookup document. */
    private static String lookupSourceUri(int index) {
        return "urn:masymos:containers-it:lookup:" + index;
    }

    /** The source URIs of the seeded documents {@code [from, to)}. */
    private static List<URI> lookupUris(int from, int to) {
        var uris = new ArrayList<URI>(to - from);
        for (var index = from; index < to; index++) {
            uris.add(URI.create(lookupSourceUri(index)));
        }
        return uris;
    }

    /** The uids of {@code versions} versions of each seeded document in {@code [from, to)}, in uid order. */
    private static List<String> expectedUids(String prefix, int from, int to) {
        var uids = new ArrayList<String>();
        for (var uri = from; uri < to; uri++) {
            for (var version = 0; version < 5; version++) {
                uids.add(String.format("%suri%03d-v%d", prefix, uri, version));
            }
        }
        return uids;
    }

    /** Removes a seeded lookup fixture: documents and their models, by the {@code fileId} prefix. */
    private static void deleteByFileIdPrefix(Session session, String prefix) {
        session.executeWrite(tx -> tx.run("""
                MATCH (document:Document)
                WHERE document.fileId STARTS WITH $prefix
                OPTIONAL MATCH (document)-[:HAS_MODEL]->(model)
                DETACH DELETE document, model
                """, Map.of("prefix", prefix)).consume());
    }

    /**
     * The fusion corpus, seeded through the same mapper convention the other fixtures use. The first
     * file has two versions whose compartment names contain the keyword once and twice, so their
     * full-text scores differ; the second file has one version. An annotation whose resource text
     * carries the keyword is mapped onto the first file's document.
     *
     * <p>The mapper writes a {@code :RESOURCE} with its {@code uri} only — {@code resourceText} is the
     * enrichment service's property ({@link Neo4jAnnotationIndexService}), not the mapper's — so the
     * indexed text is stamped the way that service stamps it, over the mapped node.
     */
    private static void insertFusionCorpus(
            Neo4jExtractedModelGraphRepository repository,
            ParsedModelGraphMapper mapper,
            String fileId,
            String otherFileId) {
        repository.insert(fusionVersion(mapper, fileId, "v1", 1, true));
        repository.insert(fusionVersion(mapper, fileId, "v2", 2, false));
        repository.insert(fusionVersion(mapper, otherFileId, "v1", 1, false));
    }

    /**
     * One version whose compartment name repeats {@code FUSION_KEYWORD}, so its score rises with the
     * repetition count. With {@code annotated}, the model also carries an annotation whose
     * {@code :RESOURCE} reproduces the enriched shape ({@code uri}, {@code uriExact},
     * {@code resourceText}); the mapper owns the nodes and the relationships, only the indexed text is
     * stamped the way {@code Neo4jAnnotationIndexService} stamps it.
     */
    private static org.masymos.core.extract.ExtractedModelGraph fusionVersion(
            ParsedModelGraphMapper mapper, String fileId, String version, int repetitions, boolean annotated) {
        var name = (FUSION_KEYWORD + " ").repeat(repetitions).trim();
        if (!annotated) {
            return mapper.toGraph(new ParsedModel(
                    new ModelReference(
                            fileId,
                            URI.create("urn:masymos:containers-it:fusion:" + fileId + ":" + version),
                            ModelType.SBML,
                            version),
                    "model_" + fileId + "_" + version,
                    "Containers IT fusion " + version,
                    Map.of(),
                    List.of(new ParsedModelEntity(
                            ParsedModelEntity.EntityType.COMPARTMENT,
                            "fusion",
                            name,
                            Map.of("id", "fusion", "name", name))),
                    List.of(),
                    List.of(),
                    List.of()));
        }
        // Deterministic on purpose: the resource URI is indexed text, so a random UUID makes the
        // annotation's score differ between runs and the fused row of this file flip between v1 and v2.
        var resourceUri = "https://example.org/fusion/annotation-" + fileId + "-" + version;
        var graph = mapper.toGraph(new ParsedModel(
                new ModelReference(
                        fileId,
                        URI.create("urn:masymos:containers-it:fusion:" + fileId + ":" + version),
                        ModelType.SBML,
                        version),
                "model_" + fileId + "_" + version,
                "Containers IT fusion " + version,
                Map.of("resourceText", FUSION_KEYWORD + " annotation"),
                List.of(new ParsedModelEntity(
                        ParsedModelEntity.EntityType.COMPARTMENT,
                        "fusion",
                        name,
                        Map.of("id", "fusion", "name", name))),
                List.of(),
                List.of(new ParsedAnnotation(
                        "model",
                        Map.of("resourceText", FUSION_KEYWORD + " annotation"),
                        List.of(new ParsedAnnotationResource(resourceUri, "isDescribedBy")),
                        List.of(),
                        List.of()))));
        var resourceKey = "resource:" + resourceUri;
        var nodes = graph.nodes().stream()
                .map(node -> node.key().equals(resourceKey)
                        ? new ExtractedGraphNode(
                                node.key(),
                                node.labels(),
                                Map.of(
                                        "uri", resourceUri,
                                        "uriExact", resourceUri,
                                        "resourceText", FUSION_KEYWORD + " annotation"))
                        : node)
                .toList();
        return new ExtractedModelGraph(graph.reference(), nodes, graph.relationships(), graph.sourceDocument());
    }

    /** The {@code Document.sourceUri} of one version: the URI a resource hit resolves to. */
    private static URI documentSourceUri(org.neo4j.driver.Session session, String fileId, String versionId) {
        return URI.create(session.executeRead(tx -> tx.run("""
                        MATCH (d:Document {fileId: $fileId, versionId: $versionId})
                        RETURN d.sourceUri AS sourceUri
                        """, Map.of("fileId", fileId, "versionId", versionId))
                .single()
                .get("sourceUri")
                .asString()));
    }

    /**
     * Asserts the fixture before anything is asserted about fusion: the annotation query must find the
     * seeded annotation and resolve it to exactly the files' document URIs, or the fusion assertions
     * would fail for a reason that has nothing to do with fusion.
     *
     * <p>D9 (QRY-10): an annotation is a fact about the model <em>file</em>, so a hit hangs on every
     * version of it, not only on the version whose graph carries the annotation. This is asserted as
     * an equality on purpose — narrowing attribution to one version would now fail here, first, with
     * the rule named.
     */
    private static void assertAnnotationHitBeforeFusion(
            Neo4jResourceQueryRepository resourceRepository, String keyword, Set<URI> expectedDocumentUris) {
        var annotations = resourceRepository.queryAnnotations(new ModelQueryRequest(keyword, List.of(), 200));
        var related = annotations.stream()
                .flatMap(hit -> hit.relatedModelUris().stream())
                .collect(Collectors.toSet());
        assertEquals(
                expectedDocumentUris,
                related,
                () -> "a resource hit resolves to every version of the file; returned " + annotations);
        annotations.forEach(hit -> assertTrue(
                hit.score() > 0.0, () -> "a full-text annotation hit scores above zero: " + hit));
    }

    /**
     * SBML-2 against a real server: the per-participant stoichiometry has to survive the Cypher write
     * as a property on the participant relationships, and a species that is reactant and product of
     * the same reversible reaction has to keep both values instead of the later write overwriting the
     * earlier one. The unit tests prove the mapper emits the property; only this proves the store
     * holds it.
     */
    @Test
    void storesPerParticipantStoichiometryOnTheRelationship() {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var fileId = "containers-it-stoichiometry.sbml";
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                repository.insert(mapper.toGraph(new ParsedModel(
                        new ModelReference(
                                fileId,
                                URI.create("urn:masymos:containers-it:stoichiometry"),
                                ModelType.SBML,
                                "v1"
                        ),
                        "containers_it_stoichiometry",
                        "Containers IT stoichiometry",
                        Map.of(),
                        List.of(
                                new ParsedModelEntity(
                                        ParsedModelEntity.EntityType.SPECIES,
                                        "a",
                                        "A",
                                        Map.of("id", "a", "name", "A")
                                ),
                                new ParsedModelEntity(
                                        ParsedModelEntity.EntityType.REACTION,
                                        "r1",
                                        "reversible",
                                        Map.of("id", "r1", "name", "reversible")
                                )
                        ),
                        List.of(
                                new ParsedReactionParticipant(
                                        "r1", "a", ParsedReactionParticipant.Role.REACTANT, Map.of("stoichiometry", "2")),
                                new ParsedReactionParticipant(
                                        "r1", "a", ParsedReactionParticipant.Role.PRODUCT, Map.of("stoichiometry", "3"))
                        )
                )));

                var stored = session.executeRead(tx -> tx.run("""
                        MATCH (:Model {modelId: $modelId})-[:HAS_REACTION]->(:SBML_REACTION)
                              -[p:HAS_REACTANT|HAS_PRODUCT]->(:SBML_SPECIES {id: 'a'})
                        RETURN type(p) AS kind, p.stoichiometry AS stoichiometry
                        """, Map.of("modelId", "containers_it_stoichiometry")).list(record -> record.get("kind")
                        .asString() + "="
                        + (record.get("stoichiometry").isNull()
                                ? "<absent>"
                                : record.get("stoichiometry").asString())));

                assertEquals(Set.of("HAS_REACTANT=2", "HAS_PRODUCT=3"), Set.copyOf(stored));
            } finally {
                session.executeWrite(tx -> tx.run("""
                        MATCH (d:Document {fileId: $fileId})
                        OPTIONAL MATCH (d)-[*0..]-(connected)
                        WITH DISTINCT connected
                        DETACH DELETE connected
                        """, Map.of("fileId", fileId)).consume());
            }
        }
    }

    /** A short rendering of a fused result for failure messages. */
    private static String describe(List<FusedModelHit> hits) {
        return hits.stream()
                .map(hit -> hit.row().fileId() + ":" + hit.row().versionId() + "=" + hit.fusedScore() + " "
                        + hit.sources())
                .toList()
                .toString();
    }

    private static List<String> fileIds(List<FusedModelHit> hits) {
        return hits.stream().map(hit -> hit.row().fileId()).toList();
    }

    /**
     * OWL-4: the traversal was written and unit-tested but had no surface. A three-class fixture makes
     * every number exact rather than plausible: {@code A} hangs off the root, {@code B} and {@code C}
     * off {@code A}, so {@code depth(B)} is 2, the lowest common ancestor of {@code B} and {@code C}
     * is {@code A} at depth 1, the distance between them is 2, and the score is the formula's value
     * for (2, 1) rather than "greater than zero".
     */
    @Test
    void answersOntologyDepthLcaAndSimilarityFromAnIndexedGraph() throws IOException {
        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");

        try (var driver = Neo4jDriverFactory.create(settings)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var service = new OwlOntologyIndexingService(
                    new ModelSourceDiscovery(),
                    new UriModelSourceContentLoader(),
                    new OwlOntologyParser(),
                    new ParsedOntologyGraphMapper(),
                    new Neo4jExtractedModelGraphRepository(driver, settings.database()));
            var fixture = Path.of("src", "test", "resources", "owl", "traversal.owl").toAbsolutePath();
            assertEquals(1, service.indexUris(List.of(fixture.toUri())).size());

            var ontology = driver.session().executeRead(tx -> tx
                    .run("MATCH (model:OWL_MODEL) RETURN model.ontology AS label")
                    .single()
                    .get("label")
                    .asString());
            assertEquals("traversalOntology", ontology, "the label the parser derives from the file name");

            var traversal = new Neo4jOntologyTraversal(driver, settings.database());

            assertEquals("owl:traversalOntology", traversal.root(ontology).orElseThrow().id());
            assertEquals("A", traversal.term(ontology, "A").orElseThrow().id());
            assertEquals(Optional.of(2), traversal.depth(ontology, "B", 32));
            assertTrue(traversal.term(ontology, "missing").isEmpty());

            var similarity = traversal.similarity(ontology, "B", "C", 32).orElseThrow();
            assertEquals("A", similarity.lowestCommonAncestor());
            assertEquals(1, similarity.lowestCommonAncestorDepth());
            assertEquals(2, similarity.pathLength());
            assertEquals(0.360, similarity.similarity(), 0.005);
            assertTrue(
                    traversal.similarity(ontology, "B", "missing", 32).isEmpty(),
                    "a term that is not indexed yields no similarity, not a score");
            assertTrue(
                    traversal.similarity(ontology, "B", "C", 1).isEmpty(),
                    "the bound applies to the distance too: B to C is 2 hops, so maxDepth 1 finds nothing");

            var parent = traversal.similarity(ontology, "A", "B", 32).orElseThrow();
            assertEquals("A", parent.lowestCommonAncestor(), "a parent is its child's lowest common ancestor");
            assertEquals(1, parent.lowestCommonAncestorDepth());
            assertEquals(1, parent.pathLength());
            assertEquals(0.440, parent.similarity(), 0.005);

            var identity = traversal.similarity(ontology, "B", "B", 32).orElseThrow();
            assertEquals("B", identity.lowestCommonAncestor());
            assertEquals(0, identity.pathLength());
            assertEquals(0.834, identity.similarity(), 0.005);
        }
    }
}

package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.model.ModelVersion;
import org.neo4j.driver.SessionConfig;

class ExternalNeo4jVersionChainIT {
    @Test
    void indexesThreeVersionsOfOneFileAsOneChain() {
        var settings = settingsFromSystemProperties();
        var testRunId = UUID.randomUUID().toString();
        var fileId = "version-chain-" + testRunId + ".sbml";
        var v1SourceUri = URI.create("urn:masymos:version-chain-it:" + testRunId + ":v1");
        var v2SourceUri = URI.create("urn:masymos:version-chain-it:" + testRunId + ":v2");
        var v3SourceUri = URI.create("urn:masymos:version-chain-it:" + testRunId + ":v3");

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(SessionConfig.builder().withDatabase(settings.database()).build())) {
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var queryRepository = new Neo4jModelRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            try {
                ModelIdentifier first = repository.insert(mapper.toGraph(new ParsedModel(
                        new ModelReference(fileId, v1SourceUri, ModelType.SBML, "v1"),
                        "version_chain_it",
                        "Version chain IT 1",
                        Map.of())));
                ModelIdentifier second = repository.insert(mapper.toGraph(new ParsedModel(
                        new ModelReference(fileId, v2SourceUri, ModelType.SBML, "v2"),
                        "version_chain_it",
                        "Version chain IT 2",
                        Map.of())));
                ModelIdentifier third = repository.insert(mapper.toGraph(new ParsedModel(
                        new ModelReference(fileId, v3SourceUri, ModelType.SBML, "v3"),
                        "version_chain_it",
                        "Version chain IT 3",
                        Map.of())));

                assertEquals(3L, count(session, """
                        MATCH (d:Document {fileId: $fileId})
                        RETURN count(d) AS count
                        """, Map.of("fileId", fileId)), "three indexed versions must be stored as three documents");

                assertEquals(2L, count(session, """
                        MATCH (:Document {fileId: $fileId})-[:HAS_SUCCESSOR]->(:Document {fileId: $fileId})
                        RETURN count(*) AS count
                        """, Map.of("fileId", fileId)), "two successors must link the three versions into one chain");
                assertEquals(2L, count(session, """
                        MATCH (:Document {fileId: $fileId})-[:HAS_PREDECESSOR]->(:Document {fileId: $fileId})
                        RETURN count(*) AS count
                        """, Map.of("fileId", fileId)), "two predecessors must mirror the successor links");

                assertChainStep(session, fileId, "v1", "v2", 1L);
                assertChainStep(session, fileId, "v2", "v3", 1L);
                assertChainStep(session, fileId, "v1", "v3", 0L);

                var latest = queryRepository.findLatestVersion(fileId);
                assertTrue(latest.isPresent(), "the latest version of " + fileId + " must be found");
                assertEquals(third.uid(), latest.orElseThrow().uid(), "the latest version must be the third insert");
                assertEquals("v3", latest.orElseThrow().versionId(), "the latest version must be v3");

                assertNotNull(first.legacyUid(), "the first insert must have a legacy uid");
                assertNotNull(second.legacyUid(), "the second insert must have a legacy uid");
                assertNotNull(third.legacyUid(), "the third insert must have a legacy uid");

                var byLegacyUid = queryRepository.findByUid(String.valueOf(first.legacyUid()));
                assertTrue(byLegacyUid.isPresent(), "legacy uid " + first.legacyUid() + " must resolve the first version");
                assertEquals(first.uid(), byLegacyUid.orElseThrow().uid(), "the legacy uid must resolve the first insert");
                assertEquals("v1", byLegacyUid.orElseThrow().versionId(), "the legacy uid must resolve the v1 document");

                var byUid = queryRepository.findByUid(second.uid());
                assertTrue(byUid.isPresent(), "uid " + second.uid() + " must resolve the second version");
                assertEquals("v2", byUid.orElseThrow().versionId(), "the uid lookup must resolve the v2 document");

                assertTrue(
                        first.legacyUid() < second.legacyUid() && second.legacyUid() < third.legacyUid(),
                        "minted legacy uids must increase: " + first.legacyUid() + ", " + second.legacyUid() + ", "
                                + third.legacyUid());

                List<ModelVersion> versions = queryRepository.findVersions(fileId);
                assertEquals(3, versions.size(), "all three versions of " + fileId + " must be listed");
                assertEquals(List.of("v1", "v2", "v3"), versions.stream().map(ModelVersion::versionId).toList());
                assertEquals(
                        List.of(first.uid(), second.uid(), third.uid()),
                        versions.stream().map(ModelVersion::uid).toList(),
                        "the version list must be ordered oldest first with the uids of the three inserts");

                var secondVersion = queryRepository.findVersion(fileId, "v2");
                assertTrue(secondVersion.isPresent(), "version v2 of " + fileId + " must be found by its label");
                assertEquals(second.uid(), secondVersion.orElseThrow().uid(), "v2 must resolve the second insert");
                assertEquals("v2", secondVersion.orElseThrow().versionId(), "the version lookup must return v2");

                assertTrue(
                        queryRepository.findVersion(fileId, "does-not-exist").isEmpty(),
                        "an unknown version label must resolve no version");
            } finally {
                // Unbounded traversal on purpose: each document of this test hangs off its own
                // model subgraph, so a hop limit would leave orphaned model/annotation nodes behind.
                session.executeWrite(tx -> tx.run("""
                        MATCH (d:Document {fileId: $fileId})
                        OPTIONAL MATCH (d)-[*0..]-(connected)
                        WITH DISTINCT connected
                        DETACH DELETE connected
                        """, Map.of("fileId", fileId)).consume());
            }
        }
    }

    private static void assertChainStep(
            org.neo4j.driver.Session session,
            String fileId,
            String from,
            String to,
            long expected
    ) {
        assertEquals(expected, count(session, """
                MATCH (:Document {fileId: $fileId, versionId: $from})-[:HAS_SUCCESSOR]->(:Document {fileId: $fileId, versionId: $to})
                RETURN count(*) AS count
                """, Map.of("fileId", fileId, "from", from, "to", to)), "chain step " + from + " -> " + to);
    }

    private static long count(org.neo4j.driver.Session session, String cypher, Map<String, Object> parameters) {
        return session.executeRead(tx -> tx.run(cypher, parameters).single().get("count").asLong());
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

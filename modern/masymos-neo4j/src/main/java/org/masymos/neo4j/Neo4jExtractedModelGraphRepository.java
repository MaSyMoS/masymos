package org.masymos.neo4j;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

public final class Neo4jExtractedModelGraphRepository implements ExtractedModelGraphRepository {
    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jExtractedModelGraphRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public ModelIdentifier insert(ExtractedModelGraph graph) {
        var graphWrite = ParsedModelGraphCypher.prepare(graph);
        try (var session = driver.session(sessionConfig)) {
            return session.executeWrite(tx -> {
                // STO-7: one transaction, many bounded statements. The prologue mints the uid, the
                // legacy uid and the document node; the node blocks then report the element id they
                // created, so the relationship blocks can address their endpoints without a scan.
                var record = tx.run(graphWrite.document().cypher(), graphWrite.document().parameters()).single();
                var uid = record.get("uid").asString();
                var fileId = record.get("fileId").asString();
                var elementIdsByKey = new HashMap<String, String>();
                elementIdsByKey.put("document", record.get("eid").asString());
                for (var statement : graphWrite.nodes()) {
                    tx.run(statement.cypher(), statement.parameters()).forEachRemaining(row ->
                            elementIdsByKey.put(row.get("nodeKey").asString(), row.get("eid").asString()));
                }
                for (var statement : graphWrite.relationshipStatements(elementIdsByKey)) {
                    tx.run(statement.cypher(), statement.parameters()).consume();
                }
                // Same transaction on purpose: a document is never written without its chain links.
                tx.run(ModelVersionChainCypher.linkPredecessorsCypher(), Map.of("uid", uid, "fileId", fileId))
                        .consume();
                return new ModelIdentifier(
                        uid,
                        fileId,
                        record.get("versionId").isNull() ? null : record.get("versionId").asString(),
                        record.get("legacyUid").asLong()
                );
            });
        }
    }

    @Override
    public void linkArchive(URI archiveUri, List<URI> entrySourceUris) {
        var archiveWrite = CombineArchiveCypher.linkArchive(archiveUri, entrySourceUris);
        try (var session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(tx -> tx.run(archiveWrite.cypher(), archiveWrite.parameters()).consume());
        }
    }
}

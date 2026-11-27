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

    /**
     * Replaces the derived graph of the document {@code documentUid} from a freshly mapped model
     * (OPN-7, ruled 2026-11-27), in one transaction, writing no new version.
     *
     * <p>The order is the ruled sequence: delete what the document owns (never the {@code Document}
     * node), {@code SET} the new properties onto that existing node, write the new graph with the
     * insert's own node and relationship blocks under the same {@code ownerUid}, then purge the shared
     * annotation nodes that lost their last relationship. Nothing bumps the counter, so a repaired
     * document keeps its {@code uid}, {@code legacyUid}, {@code versionId} and chain position.
     *
     * <p>The graph is validated before a transaction exists: a graph without its {@code document} node
     * or with unsupported labels is refused by {@link ParsedModelGraphCypher#prepareRepair} exactly as
     * {@code insert} refuses it. A {@code documentUid} no document carries fails the transaction -
     * {@code RE-EXPAND} cannot silently write a graph no document owns.
     *
     * @throws IllegalArgumentException if the graph is not one this writer can write
     * @throws IllegalStateException if no document carries {@code documentUid}
     */
    public RepairReport repair(ExtractedModelGraph graph, String documentUid) {
        var repair = ParsedModelGraphCypher.prepareRepair(graph, documentUid);
        try (var session = driver.session(sessionConfig)) {
            return session.executeWrite(tx -> {
                // OPN-7 step 1: the old generation cannot survive beside the new one. Shared nodes are
                // not owned, so the delete leaves them to the orphan purge below.
                var deletedNodes = tx.run(repair.deleteDerived().cypher(), repair.deleteDerived().parameters())
                        .single()
                        .get("nodes")
                        .asLong();

                // Step 2: the existing document, matched by uid. No CREATE, so the insert's prologue
                // never runs. Its element id is what the relationship blocks address the document by.
                var document = tx.run(repair.documentUpdate().cypher(), repair.documentUpdate().parameters())
                        .list();
                if (document.isEmpty()) {
                    throw new IllegalStateException("no document carries the uid " + documentUid);
                }
                var record = document.getFirst();
                var elementIdsByKey = new HashMap<String, String>();
                elementIdsByKey.put("document", record.get("eid").asString());

                // Step 3: the write path's own blocks, under the same ownerUid.
                for (var statement : repair.nodes()) {
                    tx.run(statement.cypher(), statement.parameters()).forEachRemaining(row ->
                            elementIdsByKey.put(row.get("nodeKey").asString(), row.get("eid").asString()));
                }
                for (var statement : repair.relationshipStatements(elementIdsByKey)) {
                    tx.run(statement.cypher(), statement.parameters()).consume();
                }

                // Step 4: only what nothing references any more. After the write, so a node this repair
                // re-references is already reachable again; UNIT is not purged (its layer has its own
                // backfill).
                var purgedNodes = 0L;
                for (var statement : ParsedModelGraphCypher.repairPurgeStatements()) {
                    purgedNodes += tx.run(statement.cypher(), statement.parameters())
                            .single()
                            .get("purged")
                            .asLong();
                }
                return new RepairReport(
                        documentUid,
                        deletedNodes,
                        (long) elementIdsByKey.size() - 1,
                        purgedNodes,
                        record.get("versionId").isNull() ? null : record.get("versionId").asString(),
                        record.get("legacyUid").isNull() ? null : record.get("legacyUid").asLong());
            });
        }
    }

    /**
     * What one repair did, per document: the nodes it deleted and wrote, the orphans it purged, and the
     * version identity it left untouched. {@code writtenNodes} counts every node the repair wrote,
     * shared nodes included; the document node is not counted because it is updated, not written.
     */
    public record RepairReport(
            String documentUid,
            long deletedNodes,
            long writtenNodes,
            long purgedNodes,
            String versionId,
            Long legacyUid
    ) {
    }

    @Override
    public void linkArchive(URI archiveUri, List<URI> entrySourceUris) {
        var archiveWrite = CombineArchiveCypher.linkArchive(archiveUri, entrySourceUris);
        try (var session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(tx -> tx.run(archiveWrite.cypher(), archiveWrite.parameters()).consume());
        }
    }
}

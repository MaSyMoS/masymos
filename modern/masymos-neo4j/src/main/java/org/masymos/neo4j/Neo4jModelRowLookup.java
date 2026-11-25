package org.masymos.neo4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.masymos.core.query.ExactText;
import org.masymos.core.query.fusion.ModelRow;
import org.masymos.core.query.fusion.ModelRows;
import org.masymos.core.spi.ModelRowLookup;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;

/**
 * The indexed resolution from document source URIs to model rows.
 *
 * <p>The match is on {@code node.sourceUriExact}, the only indexed property here
 * ({@code model_source_uri_exact}, see {@link Neo4jSchemaInitializer}); {@code Document.sourceUri}
 * has no index. The values are normalized by {@link ExactText#lowercase}, the same rule the writer
 * stamping {@code sourceUriExact} and the exact clauses use.
 */
public final class Neo4jModelRowLookup implements ModelRowLookup {
    /**
     * How many model rows one lookup may return. {@code REL-007}: 2 000 rows covers the documented
     * working range - at most 500 document URIs per ranker, four model versions per URI on average -
     * without buffering an unbounded row set in the fusion step.
     *
     * <p><strong>What was measured</strong> (2026-11-22, external Neo4j instance): 500 URIs with four
     * versions each - 2 000 matching rows - were answered complete, and 500 URIs with five versions
     * each - 2 500 matching rows - came back as 2 000, so 500 URIs at the argued four-versions sizing
     * fill this bound exactly and anything beyond loses its tail. The draw carries one version per URI
     * (66 documents, 71 model rows, one per distinct source URI); four is the re-import case, unmeasured
     * in the wild - re-importing one document four times left five rows for its URI - and nothing
     * measures how far the three rankers' 500-URI sets overlap, which with no overlap asks for 1 500.
     *
     * <p><strong>Truncation order.</strong> The statement is {@code ORDER BY uid}, so when more rows
     * match than this, the lowest {@code uid}s are kept: an arbitrary but <em>stable</em> cut, never a
     * best-scored one - the lookup carries no score to rank by.
     *
     * <p><strong>A cut is reported, not silent</strong> ({@code C03-2}, closed 2026-11-23). The
     * statement asks for {@link #requestedRows()} - one row more than it may return - and an answer
     * that carries that probe row means the store held more rows than the bound. The probe row is
     * dropped and {@link ModelRows#truncated()} is {@code true}, which
     * {@link org.masymos.core.query.fusion.RankFusionQueryService} carries to every fused hit as
     * {@code rowLookupTruncated}. {@code Neo4jContainersIT#answersAtMostTheRowBoundAndSaysSoWhenItCut}
     * measures it: 2 500 matching rows answer 2 000 with {@code truncated} true, and exactly 2 000
     * answer complete with {@code truncated} false.
     */
    static final int MAX_LOOKUP_ROWS = 2000;

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jModelRowLookup(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    /** How many rows the statement asks for: one more than it may return, so a cut is visible. */
    static int requestedRows() {
        return MAX_LOOKUP_ROWS + 1;
    }

    /**
     * Whether an answer means the store held more than the bound. The probe row is the whole
     * mechanism: {@code answeredRows == MAX_LOOKUP_ROWS} is a complete answer, not a cut.
     */
    static boolean isTruncated(int answeredRows) {
        return answeredRows > MAX_LOOKUP_ROWS;
    }

    /**
     * The {@code OPTIONAL MATCH} is mandatory to the shape: a metadata-only {@code :Document:Model}
     * node has no {@code HAS_MODEL} edge, so a mandatory match would silently drop it. The
     * {@code coalesce(document, node)} makes such a node its own document, exactly as the flat model
     * projection does, so the returned row carries the same values the model queries return.
     */
    static String lookupCypher() {
        return """
                MATCH (node:Model)
                WHERE node.sourceUriExact IN $uris
                OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)
                WITH node, coalesce(document, node) AS document
                RETURN coalesce(node.uid, document.uid) AS uid,
                       coalesce(document.fileId, node.fileId) AS fileId,
                       coalesce(node.modelId, document.fileId) AS modelId,
                       coalesce(node.name, document.fileId) AS modelName,
                       document.sourceUri AS documentUri,
                       coalesce(document.versionId, node.versionId) AS versionId,
                       document.xmlDoc IS NOT NULL AS documentStored,
                       document.xmlDocBytes AS documentBytes,
                       document.xmlDocTruncated AS documentTruncated
                ORDER BY uid
                LIMIT $limit
                """;
    }

    @Override
    public ModelRows byDocumentUris(Collection<URI> documentUris) {
        var uris = normalizedUris(documentUris);
        if (uris.isEmpty()) {
            return ModelRows.empty();
        }
        var cypher = lookupCypher();
        // HashMap, never Map.of: the parameter list may hold nulls, and Map.of rejects them.
        var parameters = new HashMap<String, Object>();
        parameters.put("uris", uris);
        // One row more than may be returned: that probe row is how a cut becomes visible, and it is
        // dropped below. Asking for exactly MAX_LOOKUP_ROWS would make "exactly 2 000 matched" and
        // "the tail was cut" the same answer.
        parameters.put("limit", requestedRows());
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                var records = tx.run(cypher, parameters).list();
                var rows = new ArrayList<ModelRow>(Math.min(records.size(), MAX_LOOKUP_ROWS));
                // The budget is counted on records, the way the server's LIMIT counts them, so a
                // record that maps to no row (no uid or fileId) does not stretch the bound.
                for (var index = 0; index < Math.min(records.size(), MAX_LOOKUP_ROWS); index++) {
                    var row = rowFrom(records.get(index));
                    if (row != null) {
                        rows.add(row);
                    }
                }
                return new ModelRows(rows, isTruncated(records.size()));
            });
        }
    }

    /** De-duplicated, normalized URIs; a null entry contributes nothing. */
    private static List<String> normalizedUris(Collection<URI> documentUris) {
        if (documentUris == null || documentUris.isEmpty()) {
            return List.of();
        }
        var uris = new LinkedHashSet<String>(documentUris.size());
        for (var documentUri : documentUris) {
            if (documentUri != null) {
                uris.add(ExactText.lowercase(documentUri.toString()));
            }
        }
        return new ArrayList<>(uris);
    }

    /**
     * Maps one record; {@code null} for a row without {@code uid} or {@code fileId}, because such a
     * row cannot be fused to a model file.
     */
    private static ModelRow rowFrom(Record record) {
        var uid = record.get("uid").asString(null);
        var fileId = record.get("fileId").asString(null);
        if (uid == null || fileId == null) {
            return null;
        }
        var documentUri = record.get("documentUri").asString(null);
        var documentBytes = record.get("documentBytes").isNull()
                ? null
                : record.get("documentBytes").asLong();
        return new ModelRow(
                uid,
                fileId,
                record.get("modelId").asString(null),
                record.get("modelName").asString(null),
                documentUri == null ? null : URI.create(documentUri),
                record.get("versionId").asString(null),
                record.get("documentStored").asBoolean(false),
                documentBytes,
                record.get("documentTruncated").asBoolean(false));
    }
}

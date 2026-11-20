package org.masymos.neo4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.masymos.core.query.ExactText;
import org.masymos.core.query.fusion.ModelRow;
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
    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jModelRowLookup(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
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
                """;
    }

    @Override
    public List<ModelRow> byDocumentUris(Collection<URI> documentUris) {
        var uris = normalizedUris(documentUris);
        if (uris.isEmpty()) {
            return List.of();
        }
        var cypher = lookupCypher();
        // HashMap, never Map.of: the parameter list may hold nulls, and Map.of rejects them.
        var parameters = new HashMap<String, Object>();
        parameters.put("uris", uris);
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                var rows = new ArrayList<ModelRow>();
                for (Record record : tx.run(cypher, parameters).list()) {
                    var row = rowFrom(record);
                    if (row != null) {
                        rows.add(row);
                    }
                }
                return List.copyOf(rows);
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

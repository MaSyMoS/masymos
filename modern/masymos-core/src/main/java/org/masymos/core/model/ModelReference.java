package org.masymos.core.model;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/**
 * Reference to a model document to be stored.
 *
 * <p>The {@code versionId} is the external version label of the document, not a counter. Legacy
 * MaSyMoS took it from the caller: a BioModels release name, a PMR commit hash, or a synthetic
 * identifier from the bulk importer. It is therefore an opaque string: never parsed, never ordered
 * numerically, and an absent or blank value means "this document carries no version label". The
 * version chain itself is built from the graph, not from this value (decision D1).
 *
 * <p>The {@code metadata} map is the free-form metadata legacy MaSyMoS received as {@code META}
 * (there a JSON string built by the caller). It is stored beside the document and served back with
 * it; an absent map is stored as no property at all.
 */
public record ModelReference(
        String fileId,
        URI sourceUri,
        ModelType modelType,
        String versionId,
        Map<String, String> metadata
) {
    public ModelReference {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(sourceUri, "sourceUri");
        Objects.requireNonNull(modelType, "modelType");
        if (fileId.isBlank()) {
            throw new IllegalArgumentException("fileId must not be blank");
        }
        versionId = versionId == null || versionId.isBlank() ? null : versionId.strip();
        metadata = copyMetadata(metadata);
    }

    private static Map<String, String> copyMetadata(Map<String, String> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        var copy = new java.util.LinkedHashMap<String, String>();
        metadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                // Map.copyOf would throw a NullPointerException, which surfaces as a 500 instead of
                // the 400 a malformed request deserves.
                throw new IllegalArgumentException("metadata keys and values must not be null or blank");
            }
            copy.put(key, value);
        });
        return Map.copyOf(copy);
    }

    public ModelReference(String fileId, URI sourceUri, ModelType modelType) {
        this(fileId, sourceUri, modelType, null, Map.of());
    }

    public ModelReference(String fileId, URI sourceUri, ModelType modelType, String versionId) {
        this(fileId, sourceUri, modelType, versionId, Map.of());
    }
}

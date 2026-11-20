package org.masymos.core.extract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * Serialises the free-form metadata map of a document.
 *
 * <p>Legacy MaSyMoS stored {@code META} as {@code gson.toJson(metaMap)}; this keeps the same shape
 * — one JSON object in a Neo4j property, because a property cannot hold a map — without keeping the
 * Gson dependency. An absent or empty map is stored as no property at all.
 */
public final class MetadataJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private MetadataJson() {
    }

    /** Returns the JSON object to store, or {@code null} when there is nothing to store. */
    public static String write(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException("metadata is not serialisable", impossible);
        }
    }

    /** Reads a stored JSON object; absent metadata is an empty map, corrupt metadata is an error. */
    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(MAPPER.readValue(json, STRING_MAP));
        } catch (JsonProcessingException corrupt) {
            throw new IllegalArgumentException("stored metadata is not a JSON string map", corrupt);
        }
    }
}

package org.masymos.core.query;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The entities the query surface can name: what a request searches, as opposed to
 * {@link QueryField}, which names what it searches on. {@link #wire()} is the lower-case form used
 * on the HTTP surface, and {@link #fromWire(String)} is its inverse. The entity vocabulary and the
 * kind vocabulary ({@code ModelType}) are deliberately separate: this type never names a model
 * kind.
 */
public enum QueryEntity {
    MODEL,
    SEDML,
    ANNOTATION,
    PERSON,
    PUBLICATION,
    ARCHIVE;

    /** The lower-case name used on the HTTP surface, for example {@code archive}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a wire name, for example {@code archive}. The match is case-insensitive; an unknown
     * value is rejected with a message that lists the accepted ones.
     */
    public static QueryEntity fromWire(String value) {
        for (QueryEntity entity : values()) {
            if (entity.wire().equalsIgnoreCase(value)) {
                return entity;
            }
        }
        throw new IllegalArgumentException("unknown query entity '" + value + "'; known: " + knownWireNames());
    }

    private static String knownWireNames() {
        return Arrays.stream(values())
                .map(QueryEntity::wire)
                .collect(Collectors.joining(", "));
    }
}

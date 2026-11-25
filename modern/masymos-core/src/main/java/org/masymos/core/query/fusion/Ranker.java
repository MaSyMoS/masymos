package org.masymos.core.query.fusion;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** The four entity rankers fused by the aggregated query; {@code wire()} is the JSON name. */
public enum Ranker {
    MODEL,
    ANNOTATION,
    PERSON,
    PUBLICATION;

    /** The lowercase spelling used on the wire and as a weight key. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a wire name to its ranker.
     *
     * @throws IllegalArgumentException if the name is unknown; the message names the valid values
     */
    public static Ranker fromWire(String wire) {
        if (wire != null) {
            for (Ranker ranker : values()) {
                if (ranker.wire().equals(wire)) {
                    return ranker;
                }
            }
        }
        throw new IllegalArgumentException(
                "unknown ranker '" + wire + "'; valid values: " + validWires());
    }

    private static String validWires() {
        return Arrays.stream(values()).map(Ranker::wire).collect(Collectors.joining(", "));
    }
}

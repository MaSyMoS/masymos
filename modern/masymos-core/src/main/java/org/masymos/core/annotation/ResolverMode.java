package org.masymos.core.annotation;

import java.util.Locale;

/**
 * How far the annotation term resolution chain may go in one indexing run.
 *
 * <p>The mode is a truncation of one chain — providers, then the page fallback, then nothing — not
 * three separate code paths. {@link #DIRECT} is the legacy network footprint and therefore the
 * default; {@link #PROVIDERS} is opt-in per run.
 */
public enum ResolverMode {

    /** BioPortal and OLS4 first, the catalogue's canonical page as the last resort. */
    PROVIDERS("providers"),

    /** The catalogue's canonical page only; no provider is asked. */
    DIRECT("direct"),

    /** Cache reads only; nothing is fetched and no cache entry is written. */
    OFF("off");

    private final String wire;

    ResolverMode(String wire) {
        this.wire = wire;
    }

    /** The lower-case wire value used by the REST parameter, the CLI flag and the recorded property. */
    public String wire() {
        return wire;
    }

    /**
     * Parses a wire value case-insensitively.
     *
     * <p>A null or blank value is the absent parameter and means {@link #DIRECT}. Any other value
     * that names no mode is rejected with an {@link IllegalArgumentException} naming the accepted
     * values, which the surface layer reports as {@code 400}.
     */
    public static ResolverMode fromWire(String value) {
        if (value == null || value.isBlank()) {
            return DIRECT;
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        for (var mode : values()) {
            if (mode.wire.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Unknown resolver mode '" + value + "'; accepted values are providers, direct, off");
    }
}

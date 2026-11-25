package org.masymos.core.query;

import java.util.Locale;

/**
 * The one normalization rule for the exact-match copies: the writer stamps them and the planners
 * query them through the same methods, so the two can never drift.
 */
public final class ExactText {
    private ExactText() {}

    /**
     * Case-insensitive key for the identity properties legacy indexed with a lowercase-keyword
     * analyzer (model {@code URI}/{@code ID}, person names, publication {@code YEAR}/{@code ID}).
     */
    public static String lowercase(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    /** Case-sensitive key for the annotation {@code URI}, which legacy did not lowercase. */
    public static String identity(String value) {
        return value == null ? null : value.strip();
    }
}

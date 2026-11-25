package org.masymos.core.query;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text preparation for full-text indexing and querying, ported from the legacy
 * {@code de.unirostock.sems.masymos.util.IndexText}. Kept free of Spring and Neo4j so the extraction
 * side and the query side can share it.
 *
 * <p>Unlike the legacy helper, which returned only the fragments, {@link #expandTermsSpecialChars}
 * is <em>additive</em>: the stored value keeps its exact form and gains the fragments behind it. In
 * legacy the original text was indexed separately by Lucene, here the aggregate property is the only
 * copy — replacing it would lose the exact identifier, e-mail address or file name.
 */
public final class IndexText {
    /** Legacy hard ceiling for a full-text term; legacy used {@code max = 32766}. */
    public static final int LEGACY_TERM_BYTE_LIMIT = 32766;

    private static final String SEPARATORS = "_:.-@/";
    private static final int EXPANSION_MIN_LENGTH = 5;
    private static final int FRAGMENT_MIN_LENGTH = 2;
    private static final int INITIAL_TRUNCATION_CHUNK = 10000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TOKEN_BOUNDARIES = Pattern.compile("[\\s_:.@/\\-]+");
    private static final Pattern NON_ASCII = Pattern.compile("[^\\x00-\\x7F]");

    private IndexText() {}

    /**
     * Ports the intent of {@code IndexText.expandTermsSpecialChars} ({@code util/IndexText.java:45})
     * additively: the value itself is returned unchanged and {@link #termFragments} is appended,
     * space separated. A value shorter than five characters, blank, or without a separator, a case
     * boundary or a letter/digit boundary is returned as it is.
     */
    public static String expandTermsSpecialChars(String value) {
        var fragments = termFragments(value);
        return fragments.isEmpty() ? value : value + " " + String.join(" ", fragments);
    }

    /**
     * The searchable fragments of a value: every whitespace token is cut at the separators
     * {@code _ : . - @ /}, at lower-to-upper case boundaries and at letter/digit boundaries, and the
     * lowercased pieces of at least two characters are returned once each, in order. Empty when the
     * value yields nothing (short, plain, or fragment-free).
     */
    public static List<String> termFragments(String value) {
        if (value == null || value.isBlank() || value.length() < EXPANSION_MIN_LENGTH || !expandable(value)) {
            return List.of();
        }
        var present = new LinkedHashSet<String>();
        for (var token : WHITESPACE.split(value.strip())) {
            present.add(token.toLowerCase(Locale.ROOT));
        }
        var fragments = new LinkedHashSet<String>();
        for (var token : WHITESPACE.split(value.strip())) {
            for (var chunk : TOKEN_BOUNDARIES.split(token)) {
                if (chunk.isEmpty()) {
                    continue;
                }
                for (var part : splitAtCaseAndDigitBoundaries(chunk)) {
                    var fragment = part.toLowerCase(Locale.ROOT);
                    if (fragment.length() >= FRAGMENT_MIN_LENGTH
                            && !containsSeparator(fragment)
                            && !present.contains(fragment)) {
                        fragments.add(fragment);
                    }
                }
            }
        }
        return List.copyOf(fragments);
    }

    /**
     * Ports {@code IndexText.shrinkTextToLuceneTermLength} ({@code util/IndexText.java:20}): text whose
     * UTF-8 length exceeds {@code LEGACY_TERM_BYTE_LIMIT - 1} is normalised to NFD, stripped of every
     * non-ASCII character and then truncated in halving chunks until it fits.
     */
    public static String shrinkToMaxTermLength(String value) {
        if (value == null || value.isBlank() || utf8Length(value) <= LEGACY_TERM_BYTE_LIMIT - 1) {
            return value;
        }
        var shortened = NON_ASCII.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
        var chunk = INITIAL_TRUNCATION_CHUNK;
        while (utf8Length(shortened) > LEGACY_TERM_BYTE_LIMIT - 1) {
            var before = shortened;
            shortened = shortened.substring(0, Math.max(0, shortened.length() - chunk));
            if (shortened.length() >= before.length()) {
                chunk = Math.max(1, chunk / 2);
            }
        }
        return shortened;
    }

    private static boolean containsSeparator(String value) {
        return value.chars().anyMatch(character -> SEPARATORS.indexOf(character) >= 0);
    }

    /** True when the value can yield fragments: a separator, or a case or letter/digit boundary. */
    private static boolean expandable(String value) {
        if (containsSeparator(value)) {
            return true;
        }
        for (var index = 1; index < value.length(); index++) {
            var previous = value.charAt(index - 1);
            var current = value.charAt(index);
            if (Character.isLowerCase(previous) && Character.isUpperCase(current)) {
                return true;
            }
            if (Character.isDigit(previous) != Character.isDigit(current)) {
                return true;
            }
        }
        return false;
    }

    /** Cuts a chunk at lower-to-upper case boundaries and at every letter/digit boundary. */
    private static List<String> splitAtCaseAndDigitBoundaries(String chunk) {
        var parts = new ArrayList<String>();
        var start = 0;
        for (var index = 1; index < chunk.length(); index++) {
            var previous = chunk.charAt(index - 1);
            var current = chunk.charAt(index);
            if ((Character.isLowerCase(previous) && Character.isUpperCase(current))
                    || Character.isDigit(previous) != Character.isDigit(current)) {
                parts.add(chunk.substring(start, index));
                start = index;
            }
        }
        parts.add(chunk.substring(start));
        return parts;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}

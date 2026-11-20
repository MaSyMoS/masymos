package org.masymos.core.annotation;

import java.util.Locale;

/**
 * The SBO convention of recording a synonym in {@code rdfs:comment} as the stub {@code "Synonym:
 * <name>"}.
 *
 * <p>Measured 2026-11-16 on the committed OLS4 captures: {@code SBO:0000323}'s comment is {@code
 * "Synonym: Kmp"} while {@code SBO:0000176}'s comment is a genuine definition ("An event involving
 * one or more chemical entities…"). The two live in the same property, so the property cannot decide
 * the bucket — the value's shape can. Reading the stub as a definition indexed the word "Synonym"
 * and counted SBO as publishing a definition it does not publish.
 *
 * <p>OLS4 surfaces the comment as {@code definition} with {@code definitionProperty:
 * rdfs:comment}; BioPortal surfaces it as {@code definition} too, without naming the property. Both
 * therefore apply the same value rule. The text is never dropped: the synonym behind the stub is
 * still keyword text, only under the name bucket.
 */
final class SynonymComment {

    private static final String STUB = "synonym:";

    private SynonymComment() {
    }

    /**
     * The synonym behind the stub, without the stub word and its separator, or {@code null} when the
     * text is not a stub. Case-insensitive, because the prefix is a vocabulary convention and not a
     * contract.
     */
    static String synonym(String text) {
        if (text == null) {
            return null;
        }
        var stripped = text.strip();
        if (!stripped.toLowerCase(Locale.ROOT).startsWith(STUB)) {
            return null;
        }
        var synonym = stripped.substring(STUB.length()).strip();
        return synonym.isEmpty() ? null : synonym;
    }
}

package org.masymos.core.annotation;

/**
 * Identity of one ontology term: a canonical prefix and its local identifier.
 *
 * <p>This is the cache key, the log identity and the recorded {@code termKey}. The prefix is always
 * the canonical lower-case spelling (aliases such as {@code obo.go} resolve before a key is built),
 * and the id is the canonical local identifier without the prefix token (see
 * {@link AnnotationPrefixCatalogue#termKey(String)}).
 */
public record TermKey(String prefix, String id) {

    public TermKey {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("Term key prefix must not be blank");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Term key id must not be blank");
        }
        prefix = prefix.trim();
        id = id.trim();
    }

    /** The wire form {@code prefix:id}, for example {@code go:0008150}. */
    public String wire() {
        return prefix + ":" + id;
    }

    @Override
    public String toString() {
        return wire();
    }
}

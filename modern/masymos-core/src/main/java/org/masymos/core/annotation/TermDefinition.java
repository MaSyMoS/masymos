package org.masymos.core.annotation;

import java.util.Objects;

/**
 * What is known about one ontology term: its keyword text, the source that answered, and — when it
 * could not be resolved — why, and whether the failure is worth retrying.
 *
 * <p>The failure class is what keeps the cache honest: {@link FailureClass#DEFINITIVE} answers are
 * fresh until the TTL expires, {@link FailureClass#TRANSIENT} entries are stored for diagnosis but
 * never treated as fresh, so the next run retries them.
 *
 * <p><strong>Status is metadata, not a failure.</strong> A resolved definition may carry an
 * {@link TermStatus} saying the cited term is obsolete or was merged into a successor. That is a
 * valid citation (D11): the keyword text is then the successor's published text, {@link #key()} stays
 * the <em>cited</em> key, and the status is never a reason to record a negative. The "resolved must
 * not carry a failure class or reason" invariant is untouched by it.
 */
public record TermDefinition(
        TermKey key,
        Status status,
        String keywordText,
        Source source,
        FailureClass failureClass,
        String failureReason,
        TermStatus termStatus
) {

    /**
     * The canonical shape plus the status. A {@code null} status means {@link TermStatus#none()}, so no
     * caller has to think about it.
     */
    public TermDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        keywordText = keywordText == null ? "" : keywordText;
        termStatus = termStatus == null ? TermStatus.none() : termStatus;
        if (status == Status.RESOLVED) {
            if (failureClass != null) {
                throw new IllegalArgumentException("A resolved term must not carry a failure class: " + key.wire());
            }
            if (failureReason != null) {
                throw new IllegalArgumentException("A resolved term must not carry a failure reason: " + key.wire());
            }
        } else if (failureClass == null) {
            throw new IllegalArgumentException("An unresolved term must carry a failure class: " + key.wire());
        }
    }

    /** The six-component shape every pre-status call site used; the status is {@link TermStatus#none()}. */
    public TermDefinition(
            TermKey key,
            Status status,
            String keywordText,
            Source source,
            FailureClass failureClass,
            String failureReason
    ) {
        this(key, status, keywordText, source, failureClass, failureReason, TermStatus.none());
    }

    /** A term whose keyword text was obtained from {@code source}. */
    public static TermDefinition resolved(TermKey key, String keywordText, Source source) {
        return new TermDefinition(key, Status.RESOLVED, keywordText, source, null, null);
    }

    /**
     * A term whose keyword text was obtained from {@code source} and whose lifecycle status the
     * provider reported. For a merged id the text is the successor's while {@code key} stays the
     * cited key (D11).
     */
    public static TermDefinition resolved(TermKey key, String keywordText, Source source, TermStatus status) {
        return new TermDefinition(key, Status.RESOLVED, keywordText, source, null, null, status);
    }

    /** A term that could not be answered, with the class that decides whether it is retried. */
    public static TermDefinition unresolved(TermKey key, FailureClass failureClass, String failureReason) {
        return new TermDefinition(key, Status.UNRESOLVED, "", null, failureClass, failureReason);
    }

    public boolean resolved() {
        return status == Status.RESOLVED;
    }

    public enum Status {
        RESOLVED,
        UNRESOLVED
    }

    public enum Source {
        BIOPORTAL,
        OLS4,
        /** The QuickGO ontology service, the GO provider of the term stage (ANNO-1g). */
        QUICKGO,
        PAGE
    }

    public enum FailureClass {
        /** The provider answered "no such class", or no configured provider serves the prefix. */
        DEFINITIVE,
        /** Timeout, 5xx, demoted provider, empty extraction, unparsable body, or no text source configured: retry next run. */
        TRANSIENT
    }
}

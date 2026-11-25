package org.masymos.core.annotation;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Port for the persisted term cache: what is known about a term, plus when it was learned.
 *
 * <p>The cache is knowledge about ontologies, not data about documents. It is read once and written
 * once per annotation run, and it holds {@link TermDefinition.FailureClass#TRANSIENT} entries for
 * diagnosis even though the resolver never treats them as fresh.
 *
 * <p>The core module owns this port only; the implementation (a {@code :ONTOLOGY_TERM} store) lives
 * in {@code masymos-neo4j}.
 */
public interface AnnotationTermCache {

    /** The cached entry for every requested key that has one; keys without an entry are absent. */
    Map<TermKey, CachedTerm> findAll(Collection<TermKey> keys);

    /** Upserts one entry per term. */
    void saveAll(Collection<CachedTerm> terms);

    /**
     * A cached {@link TermDefinition}, the instant it was fetched — which the TTL is measured against —
     * and the {@linkplain AnnotationPrefixCatalogue#lookupFingerprint(TermKey) lookup fingerprint} of
     * the question that produced it.
     *
     * <p>{@code catalogueFingerprint} is nullable: {@code null} means the entry was written before the
     * fingerprint existed, so the question behind it is <em>unknown</em>. The resolver never treats a
     * negative answer with an unknown or different fingerprint as fresh, so such an entry heals itself
     * on its next read; a resolved answer is exempt because a 200 body cannot belong to a different
     * question.
     *
     * @param definition the answer, resolved or unresolved
     * @param fetchedAt when the answer was fetched; the TTL is measured against it
     * @param catalogueFingerprint the catalogue identity plus resolution strategy the lookup used, or
     *         {@code null} for an entry written before that was recorded
     */
    record CachedTerm(TermDefinition definition, Instant fetchedAt, String catalogueFingerprint) {

        public CachedTerm {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(fetchedAt, "fetchedAt");
        }

        /**
         * An entry whose lookup question is not recorded — for callers that do not produce cache
         * knowledge themselves, such as a store reading rows written before the fingerprint existed.
         */
        public CachedTerm(TermDefinition definition, Instant fetchedAt) {
            this(definition, fetchedAt, null);
        }

        public TermKey key() {
            return definition.key();
        }

        /** An entry whose lookup question is not recorded; see {@link #catalogueFingerprint()}. */
        public static CachedTerm of(TermDefinition definition, Instant fetchedAt) {
            return new CachedTerm(definition, fetchedAt, null);
        }

        /**
         * An entry that records the question that produced it, which is what lets a stale negative be
         * recognised later.
         */
        public static CachedTerm of(TermDefinition definition, Instant fetchedAt, String catalogueFingerprint) {
            return new CachedTerm(definition, fetchedAt, catalogueFingerprint);
        }
    }
}

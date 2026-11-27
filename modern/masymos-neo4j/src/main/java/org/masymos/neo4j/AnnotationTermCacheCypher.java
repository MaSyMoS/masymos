package org.masymos.neo4j;

/**
 * Cypher for the persisted term cache: the {@code :ONTOLOGY_TERM} uniqueness constraint, one
 * batched read and one batched upsert.
 *
 * <p>Extracted as static strings so the shape stays unit-testable without a database, the way
 * {@link AnnotationPendingResourceQuery} is. The nodes are shared knowledge about ontologies, never
 * document data: no statement here mentions {@code ownerUid} or {@code resourceText}, so neither the
 * delete path nor {@code dropExistingIndex} can reach a cached term through this class.
 */
public final class AnnotationTermCacheCypher {

    private static final String CONSTRAINT = """
            CREATE CONSTRAINT ontologyTermKey IF NOT EXISTS
            FOR (t:ONTOLOGY_TERM) REQUIRE t.key IS UNIQUE
            """;
    private static final String FIND_ALL = """
            UNWIND $keys AS key
            MATCH (t:ONTOLOGY_TERM {key: key})
            RETURN t.key AS key, t.status AS status, t.keywordText AS keywordText, t.source AS source,
                   t.failureClass AS failureClass, t.fetchedAt AS fetchedAt, t.failureReason AS failureReason,
                   t.catalogueFingerprint AS catalogueFingerprint, t.termObsolete AS termObsolete,
                   t.termReplacedBy AS termReplacedBy, t.termReason AS termReason,
                   t.termStatusSource AS termStatusSource
            """;
    private static final String SAVE_ALL = """
            UNWIND $terms AS term
            MERGE (t:ONTOLOGY_TERM {key: term.key})
            SET   t.prefix = term.prefix, t.id = term.id, t.status = term.status,
                  t.keywordText = term.keywordText, t.source = term.source,
                  t.failureClass = term.failureClass, t.fetchedAt = term.fetchedAt,
                  t.failureReason = term.failureReason, t.catalogueFingerprint = term.catalogueFingerprint,
                  t.termObsolete = term.termObsolete, t.termReplacedBy = term.termReplacedBy,
                  t.termReason = term.termReason, t.termStatusSource = term.termStatusSource
            """;

    private AnnotationTermCacheCypher() {
    }

    /** The one constraint the cache needs: {@code ONTOLOGY_TERM.key} unique, which backs the MERGE. */
    public static String constraint() {
        return CONSTRAINT;
    }

    /** Reads the entries for {@code $keys}; keys without an entry are simply absent from the result. */
    public static String findAll() {
        return FIND_ALL;
    }

    /** Upserts one entry per element of {@code $terms}, keyed by the term's wire form. */
    public static String saveAll() {
        return SAVE_ALL;
    }
}

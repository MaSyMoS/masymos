package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The term cache's Cypher, asserted as text the way {@link AnnotationPendingResourceQuery} is.
 *
 * <p>No database is involved: these tests pin the statement shape, the property names both sides of
 * the cache agree on, and the one invariant that matters most &mdash; that nothing here can touch
 * document-owned enrichment.
 */
class AnnotationTermCacheCypherTest {

    private static final List<String> STATEMENTS = List.of(
            AnnotationTermCacheCypher.constraint(),
            AnnotationTermCacheCypher.findAll(),
            AnnotationTermCacheCypher.saveAll());

    @Test
    void constraintIsTheDesignStatementOnTheOntologyTermKey() {
        var constraint = AnnotationTermCacheCypher.constraint();

        assertTrue(constraint.contains("CREATE CONSTRAINT ontologyTermKey IF NOT EXISTS"), constraint);
        assertTrue(constraint.contains("FOR (t:ONTOLOGY_TERM) REQUIRE t.key IS UNIQUE"), constraint);
    }

    @Test
    void schemaInitializerCreatesTheSameConstraint() {
        assertTrue(Neo4jSchemaInitializer.statements().contains(AnnotationTermCacheCypher.constraint()));
    }

    @Test
    void schemaInitializerAddsNoFulltextIndexForTheTermCache() {
        assertFalse(Neo4jSchemaInitializer.statements().stream()
                .anyMatch(statement -> statement.contains("FULLTEXT")
                        && statement.contains("ONTOLOGY_TERM")));
    }

    @Test
    void readUnwindsTheKeysAndReturnsOnlyThePropertiesTheCacheStores() {
        var read = AnnotationTermCacheCypher.findAll();

        assertTrue(read.startsWith("UNWIND $keys AS key"), read);
        assertTrue(read.contains("MATCH (t:ONTOLOGY_TERM {key: key})"), read);
        for (var property : List.of(
                "t.key AS key",
                "t.status AS status",
                "t.keywordText AS keywordText",
                "t.source AS source",
                "t.failureClass AS failureClass",
                "t.fetchedAt AS fetchedAt",
                "t.failureReason AS failureReason",
                "t.catalogueFingerprint AS catalogueFingerprint",
                "t.termObsolete AS termObsolete",
                "t.termReplacedBy AS termReplacedBy",
                "t.termReason AS termReason",
                "t.termStatusSource AS termStatusSource")) {
            assertTrue(read.contains(property), property + " missing in:\n" + read);
        }
        // A MATCH, not an OPTIONAL MATCH: an absent key is simply absent from the result.
        assertFalse(read.contains("OPTIONAL MATCH"), read);
    }

    @Test
    void writeUnwindsTheTermsAndMergesOnTheWireKey() {
        var write = AnnotationTermCacheCypher.saveAll();

        assertTrue(write.startsWith("UNWIND $terms AS term"), write);
        assertTrue(write.contains("MERGE (t:ONTOLOGY_TERM {key: term.key})"), write);
        for (var property : List.of(
                "t.prefix = term.prefix",
                "t.id = term.id",
                "t.status = term.status",
                "t.keywordText = term.keywordText",
                "t.source = term.source",
                "t.failureClass = term.failureClass",
                "t.fetchedAt = term.fetchedAt",
                "t.failureReason = term.failureReason",
                "t.catalogueFingerprint = term.catalogueFingerprint",
                "t.termObsolete = term.termObsolete",
                "t.termReplacedBy = term.termReplacedBy",
                "t.termReason = term.termReason",
                "t.termStatusSource = term.termStatusSource")) {
            assertTrue(write.contains(property), property + " missing in:\n" + write);
        }
        // The MERGE is the whole upsert: no CREATE, so a repeated run cannot mint a second node.
        assertFalse(write.contains("CREATE (t:ONTOLOGY_TERM"), write);
    }

    @Test
    void noStatementTouchesDocumentOwnedEnrichment() {
        for (var statement : STATEMENTS) {
            assertFalse(statement.contains("ownerUid"), statement);
            assertFalse(statement.contains("resourceText"), statement);
            assertFalse(statement.contains("RESOURCE"), statement);
        }
    }
}

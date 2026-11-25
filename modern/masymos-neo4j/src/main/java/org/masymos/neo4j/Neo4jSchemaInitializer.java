package org.masymos.neo4j;

import java.util.List;
import org.masymos.core.spi.SchemaInitializer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

public final class Neo4jSchemaInitializer implements SchemaInitializer {
    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jSchemaInitializer(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public void initialize() {
        try (var session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(tx -> {
                for (var statement : statements()) {
                    tx.run(statement);
                }
            });
        }
    }

    /** The declaration text of every constraint and index, in the order the initializer applies them. */
    static List<String> statements() {
        return STATEMENTS;
    }

    private static final List<String> STATEMENTS = List.of(
            """
                    CREATE CONSTRAINT document_uid IF NOT EXISTS
                    FOR (d:Document)
                    REQUIRE d.uid IS UNIQUE
                    """,
            """
                    CREATE CONSTRAINT combine_archive_uid IF NOT EXISTS
                    FOR (a:CombineArchive)
                    REQUIRE a.uid IS UNIQUE
                    """,
            // A uniqueness constraint, not a plain index: it backs the MERGE that mints the
            // numeric legacy uid, so two concurrent inserts cannot create two counter nodes
            // (legacy relied on the same single-node assumption, IdFactory.java:24).
            """
                    CREATE CONSTRAINT global_unique_id IF NOT EXISTS
                    FOR (id:GlobalUniqueId)
                    REQUIRE id.count IS UNIQUE
                    """,
            // CML-1: units are shared nodes merged on their canonical key, so the key has to be
            // unique or two concurrent writes could create two nodes for one definition.
            """
                    CREATE CONSTRAINT unit_key_unique IF NOT EXISTS
                    FOR (u:UNIT)
                    REQUIRE u.key IS UNIQUE
                    """,
            """
                    CREATE INDEX document_file_id IF NOT EXISTS
                    FOR (d:Document)
                    ON (d.fileId)
                    """,
            """
                    CREATE INDEX document_file_version IF NOT EXISTS
                    FOR (d:Document)
                    ON (d.fileId, d.versionId)
                    """,
            """
                    CREATE INDEX document_legacy_uid IF NOT EXISTS
                    FOR (d:Document)
                    ON (d.legacyUid)
                    """,
            // QRY-8: the equality predicates of the exact clauses run against these copies, so
            // they need a B-tree index. A metadata-only node is :Document:Model, so
            // model_source_uri_exact covers it and the Document row needs no index of its own.
            """
                    CREATE INDEX model_source_uri_exact IF NOT EXISTS
                    FOR (m:Model)
                    ON (m.sourceUriExact)
                    """,
            """
                    CREATE INDEX model_model_id_exact IF NOT EXISTS
                    FOR (m:Model)
                    ON (m.modelIdExact)
                    """,
            """
                    CREATE INDEX resource_uri_exact IF NOT EXISTS
                    FOR (r:RESOURCE)
                    ON (r.uriExact)
                    """,
            """
                    CREATE INDEX person_family_name_exact IF NOT EXISTS
                    FOR (p:PERSON)
                    ON (p.familyNameExact)
                    """,
            """
                    CREATE INDEX person_given_name_exact IF NOT EXISTS
                    FOR (p:PERSON)
                    ON (p.givenNameExact)
                    """,
            """
                    CREATE INDEX publication_pub_id_exact IF NOT EXISTS
                    FOR (p:PUBLICATION)
                    ON (p.pubIdExact)
                    """,
            """
                    CREATE INDEX publication_year_exact IF NOT EXISTS
                    FOR (p:PUBLICATION)
                    ON (p.yearExact)
                    """,
            """
                    CREATE FULLTEXT INDEX modelFulltextV6 IF NOT EXISTS
                    FOR (m:Model)
                    ON EACH [
                      m.fileId,
                      m.modelId,
                      m.name,
                      m.versionId,
                      m.component,
                      m.variable,
                      m.compartment,
                      m.species,
                      m.reaction,
                      m.parameter,
                      m.geneProduct,
                      m.modelSource,
                      m.outputType,
                      m.dataLabel,
                      m.simKisao,
                      m.simType,
                      m.math,
                      m.resourceUri,
                      m.resourceText,
                      m.nonRdf,
                      m.rule,
                      m.function,
                      m.event,
                      m.creator,
                      m.author,
                      m.title,
                      m.abstractText,
                      m.affiliation,
                      m.journal,
                      m.year,
                      m.familyName,
                      m.givenName,
                      m.email,
                      m.organization,
                      m.sourceUri,
                      m.sbmlLevel,
                      m.sbmlVersion,
                      m.cellmlVersion,
                      m.sedmlLevel,
                      m.sedmlVersion,
                      m.keywords
                    ]
                    """,
            // Generation bumps for a searchable field: V5 added m.keywords (CML-3) and V6 adds
            // m.geneProduct (FBC-2, decision 52). A FULLTEXT index's field list cannot be widened in
            // place, so every bump creates a new generation and the migration drops the older ones -
            // no query may be served from a stale field list. The cost is the documented one: a
            // document indexed before the bump is not in the new index until it is imported again.
            "DROP INDEX modelFulltextV5 IF EXISTS",
            "DROP INDEX modelFulltextV4 IF EXISTS",
            "DROP INDEX modelFulltextV3 IF EXISTS",
            "DROP INDEX modelFulltextV2 IF EXISTS",
            """
                    CREATE FULLTEXT INDEX annotationFulltextV1 IF NOT EXISTS
                    FOR (r:RESOURCE)
                    ON EACH [
                      r.uri,
                      r.resourceText,
                      r.nonRdf
                    ]
                    """,
            """
                    CREATE FULLTEXT INDEX personFulltextV1 IF NOT EXISTS
                    FOR (p:PERSON)
                    ON EACH [
                      p.givenName,
                      p.familyName,
                      p.email,
                      p.organization
                    ]
                    """,
            """
                    CREATE FULLTEXT INDEX publicationFulltextV1 IF NOT EXISTS
                    FOR (p:PUBLICATION)
                    ON EACH [
                      p.pubId,
                      p.title,
                      p.journal,
                      p.abstractText,
                      p.affiliation,
                      p.year,
                      p.author
                    ]
                    """,
            // QRY-9: archives are searched by their own two properties only; Neo4j populates a
            // new full-text index from existing nodes, so the joined archives need no backfill.
            """
                    CREATE FULLTEXT INDEX archiveFulltextV1 IF NOT EXISTS
                    FOR (a:CombineArchive)
                    ON EACH [a.fileId, a.sourceUri]
                    """,
            // ANNO-1a: the term cache's one constraint, which backs the cache's MERGE. The nodes are
            // shared knowledge about ontologies (no ownerUid, never purged), so no index is added for
            // them beyond this uniqueness constraint.
            AnnotationTermCacheCypher.constraint()
    );
}

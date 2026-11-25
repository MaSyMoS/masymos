package org.masymos.neo4j;

/**
 * The Cypher of the unit backfill (STO-6). Separate from {@link ParsedModelGraphCypher} on purpose:
 * that builder writes a whole document and mints its {@code ownerUid}, while this one must touch
 * nothing but the unit layer of a document that already exists. Nothing here creates a node with an
 * owner, changes a version link, or reads an entity's properties.
 *
 * <p>Every write is a {@code MERGE} keyed on what the normal write keys on - the unit's canonical
 * {@code key}, and the definition edge's {@code (start, end)} pair - so a second run of the same
 * backfill matches everything it wrote the first time and creates nothing. The pair is the edge's
 * identity; exponent and multiplier describe the one definition that survives (UNIT-002).
 */
final class UnitBackfillCypher {

    private UnitBackfillCypher() {
    }

    /** How many {@code Document} nodes exist at all, stored source or not. */
    static final String DOCUMENT_COUNT =
            "MATCH (d:Document) RETURN count(d) AS count";

    /**
     * The documents the backfill can read, in a deterministic order, from a page position onwards.
     * {@code $after} is the cursor {@code C04-2} added: {@code null} starts at the beginning, and any
     * other value returns only the documents that sort after it. The comparison is a <em>keyset</em>,
     * not an offset: {@code d.uid} carries the {@code document_uid} uniqueness constraint, so it is
     * non-null and total, and a delete cannot shift a page boundary the way {@code SKIP} would.
     *
     * <p>{@code xmlDocTruncated} travels with the row: a truncated document is not a parseable source,
     * so it is skipped by reason rather than parsed into a wrong graph.
     */
    static final String STORED_DOCUMENTS = """
            MATCH (d:Document)
            WHERE d.xmlDoc IS NOT NULL
              AND ($after IS NULL OR d.uid > $after)
            RETURN d.uid AS uid,
                   coalesce(d.fileId, d.uid) AS fileId,
                   d.sourceUri AS sourceUri,
                   d.modelType AS modelType,
                   d.versionId AS versionId,
                   d.xmlDocEncoding AS encoding,
                   coalesce(d.xmlDocTruncated, false) AS truncated,
                   d.xmlDoc AS xml
            ORDER BY uid
            """;

    /**
     * The same rows, cut to a bound {@code T-018} sets: {@code LIMIT $limit} keeps the statement text
     * fixed, so a bounded run of a corpus of any size is still one statement of one shape.
     */
    static final String STORED_DOCUMENTS_LIMITED = STORED_DOCUMENTS + "LIMIT $limit\n";

    /** How many {@code Document} nodes carry a stored source - the population a bounded run samples. */
    static final String STORED_DOCUMENT_COUNT =
            "MATCH (d:Document) WHERE d.xmlDoc IS NOT NULL RETURN count(d) AS count";

    /**
     * The stored documents that lie after a page position: what a bounded run leaves for a later one.
     * It counts the same population {@link #STORED_DOCUMENTS} reads, from the same position, or
     * {@code documentsNotScanned} would describe a corpus the scan is not sampling.
     */
    static final String STORED_DOCUMENTS_AFTER_COUNT = """
            MATCH (d:Document)
            WHERE d.xmlDoc IS NOT NULL
              AND ($after IS NULL OR d.uid > $after)
            RETURN count(d) AS count
            """;

    /**
     * A document that already has a unit edge is left alone without reading its source: the point of
     * the run is documents from before CML-1, and re-deriving a complete one could only add noise to
     * the report.
     */
    static final String ALREADY_COMPLETE = """
            MATCH (d:Document {uid: $uid})
            WHERE EXISTS { MATCH (d)-[:HAS_MODEL]->(:Model)-[:USES_UNIT]->(:UNIT) }
            RETURN count(d) AS count
            """;

    /** The unit keys of the layer that the graph does not know yet. */
    static final String MISSING_UNITS = """
            UNWIND $keys AS key
            MATCH (u:UNIT {key: key})
            RETURN collect(u.key) AS present
            """;

    /** The definition edges of the layer that the graph does not have yet. */
    static final String MISSING_COMPOSITIONS = """
            UNWIND $compositions AS edge
            MATCH (a:UNIT {key: edge.start})-[r:COMPOSED_OF]->(b:UNIT {key: edge.end})
            RETURN count(r) AS count
            """;

    /** The {@code USES_UNIT} edges of the layer that the graph does not have yet. */
    static final String MISSING_USES = """
            UNWIND $uses AS key
            MATCH (:Document {uid: $uid})-[:HAS_MODEL]->(m:Model)-[r:USES_UNIT]->(u:UNIT {key: key})
            RETURN count(r) AS count
            """;

    /**
     * The {@code HAS_UNIT} edges of the layer that the graph does not have yet. The start is matched
     * by the label the entity was written with and its {@code id}, scoped to the document by
     * {@code ownerUid}: those are the only two facts of an entity a later run can still see.
     */
    static String missingHasUnits(String label) {
        return """
                UNWIND $hasUnits AS edge
                MATCH (e:`%s` {ownerUid: $uid, id: edge.id})-[r:HAS_UNIT]->(u:UNIT {key: edge.unitKey})
                RETURN count(r) AS count
                """.formatted(label);
    }

    /**
     * How many of the layer's {@code HAS_UNIT} edges have an entity to start from at all. The
     * difference to the batch size is what a run has to report as skipped: an entity the document no
     * longer has (or never had) cannot carry an edge.
     */
    static String resolvableHasUnits(String label) {
        return """
                UNWIND $hasUnits AS edge
                MATCH (e:`%s` {ownerUid: $uid, id: edge.id})
                RETURN count(DISTINCT edge.id) AS count
                """.formatted(label);
    }

    /** Writes the {@code UNIT} nodes, {@code MERGE}d on the canonical key like the normal write. */
    static final String WRITE_UNITS = """
            UNWIND $units AS unit
            MERGE (u:UNIT {key: unit.key})
            SET u += unit.properties
            """;

    /**
     * Writes the definition edges; identity is the endpoint pair. Exponent and multiplier are set
     * afterwards, so the edge carries the definition the backfill read - the last writer wins if two
     * documents define the same pair differently (UNIT-002).
     */
    static final String WRITE_COMPOSITIONS = """
            UNWIND $compositions AS edge
            MATCH (a:UNIT {key: edge.start}), (b:UNIT {key: edge.end})
            MERGE (a)-[r:COMPOSED_OF]->(b)
            SET r.exponent = edge.exponent, r.multiplier = edge.multiplier
            """;

    /** Writes the {@code USES_UNIT} edges from this document's model to its units. */
    static final String WRITE_USES = """
            UNWIND $uses AS key
            MATCH (d:Document {uid: $uid})-[:HAS_MODEL]->(m:Model)
            MATCH (u:UNIT {key: key})
            MERGE (m)-[r:USES_UNIT]->(u)
            """;

    /** Writes the {@code HAS_UNIT} edges from the entities that declare a unit. */
    static String writeHasUnits(String label) {
        return """
                UNWIND $hasUnits AS edge
                MATCH (e:`%s` {ownerUid: $uid, id: edge.id})
                MATCH (u:UNIT {key: edge.unitKey})
                MERGE (e)-[r:HAS_UNIT]->(u)
                SET r += edge.properties
                """.formatted(label);
    }
}

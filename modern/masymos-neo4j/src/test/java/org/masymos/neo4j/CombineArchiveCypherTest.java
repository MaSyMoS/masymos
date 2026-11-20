package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class CombineArchiveCypherTest {
    @Test
    void mergesArchiveNodeKeyedBySourceUri() {
        var write = CombineArchiveCypher.linkArchive(
                URI.create("file:/tmp/example.omex"),
                List.of());

        assertTrue(write.cypher().contains("MERGE (archive:CombineArchive {sourceUri: $archiveUri})"));
        assertTrue(write.cypher().contains("ON CREATE SET archive.uid = $archiveUri, archive.fileId = $archiveFileId"));
        assertEquals("file:/tmp/example.omex", write.parameters().get("archiveUri"));
        assertEquals("example.omex", write.parameters().get("archiveFileId"));
        assertEquals(List.of(), write.parameters().get("entries"));
    }

    @Test
    void matchesDocumentsPerEntryAndMergesHasDocument() {
        var write = CombineArchiveCypher.linkArchive(
                URI.create("file:/tmp/example.omex"),
                List.of(URI.create("file:/tmp/spec1.sbml"), URI.create("file:/tmp/spec2.cellml")));

        assertTrue(write.cypher().contains("UNWIND $entries AS entry"));
        assertTrue(write.cypher().contains("MATCH (document:Document {sourceUri: entry})"));
        assertTrue(write.cypher().contains("MERGE (archive)-[:HAS_DOCUMENT]->(document)"));
        assertFalse(write.cypher().contains("CREATE (archive"));
        assertEquals(
                List.of("file:/tmp/spec1.sbml", "file:/tmp/spec2.cellml"),
                write.parameters().get("entries"));
    }

    @Test
    void derivesFileIdFromLastPathSegment() {
        assertEquals("archive.omex", CombineArchiveCypher.fileId(URI.create("http://example.org/a/b/archive.omex")));
        // A URI without a path keeps its full form, matching ModelSourceDiscovery.deriveFileId.
        assertEquals("http://example.org", CombineArchiveCypher.fileId(URI.create("http://example.org")));
        assertEquals("x.omex", CombineArchiveCypher.fileId(URI.create("file:///data/x.omex")));
    }

    @Test
    void rejectsMissingArchiveUri() {
        assertThrows(IllegalArgumentException.class, () -> CombineArchiveCypher.linkArchive(null, List.of()));
    }
}

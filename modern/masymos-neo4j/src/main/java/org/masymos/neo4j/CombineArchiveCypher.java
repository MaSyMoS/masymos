package org.masymos.neo4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CombineArchiveCypher {
    private CombineArchiveCypher() {
    }

    static PreparedGraphWrite linkArchive(URI archiveUri, List<URI> entrySourceUris) {
        if (archiveUri == null) {
            throw new IllegalArgumentException("archiveUri must not be null");
        }
        var archiveSourceUri = archiveUri.toString();
        var entries = new ArrayList<String>();
        if (entrySourceUris != null) {
            for (var entrySourceUri : entrySourceUris) {
                if (entrySourceUri != null) {
                    entries.add(entrySourceUri.toString());
                }
            }
        }
        return new PreparedGraphWrite("""
                MERGE (archive:CombineArchive {sourceUri: $archiveUri})
                ON CREATE SET archive.uid = $archiveUri, archive.fileId = $archiveFileId
                WITH archive
                UNWIND $entries AS entry
                MATCH (document:Document {sourceUri: entry})
                MERGE (archive)-[:HAS_DOCUMENT]->(document)
                """, Map.of(
                "archiveUri", archiveSourceUri,
                "archiveFileId", fileId(archiveUri),
                "entries", List.copyOf(entries)
        ));
    }

    static String fileId(URI sourceUri) {
        var path = sourceUri.getPath();
        if (path == null || path.isEmpty()) {
            return sourceUri.toString();
        }
        var trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        var lastSegment = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return lastSegment.isEmpty() ? sourceUri.toString() : lastSegment;
    }
}

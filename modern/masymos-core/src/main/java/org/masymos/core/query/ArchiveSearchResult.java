package org.masymos.core.query;

import java.net.URI;

/**
 * One COMBINE archive hit. {@code documentCount} is the number of documents linked to the archive,
 * {@code 0} for an archive without documents; the entries themselves are not reported as rows.
 */
public record ArchiveSearchResult(
        String uid,
        String fileId,
        URI sourceUri,
        double score,
        long documentCount
) {}

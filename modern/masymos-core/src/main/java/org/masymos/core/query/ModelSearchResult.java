package org.masymos.core.query;

import java.net.URI;
import java.util.List;

/**
 * One model search hit. {@code matchedFields} is <em>request</em> provenance: the fields the caller
 * constrained, not a per-hit explanation of the score; {@code indexSource} names the index the hit
 * came from ({@code modelFulltextV4} or {@code exact}). The stored source document is reported as
 * presence, original size and truncation flag only, never inline.
 */
public record ModelSearchResult(
        String uid,
        String modelId,
        String modelName,
        URI documentUri,
        String filename,
        double score,
        List<ModelVersionHit> versions,
        String versionId,
        List<QueryField> matchedFields,
        String indexSource,
        boolean documentStored,
        Long documentBytes,
        boolean documentTruncated
) {
    public ModelSearchResult {
        versions = versions == null ? List.of() : List.copyOf(versions);
        matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
    }

    /** A flat result without version grouping; {@code versions} is empty. */
    public ModelSearchResult(
            String uid,
            String modelId,
            String modelName,
            URI documentUri,
            String filename,
            double score) {
        this(uid, modelId, modelName, documentUri, filename, score, List.of());
    }

    /** A grouped result with its versions; the request and document facts are left unset. */
    public ModelSearchResult(
            String uid,
            String modelId,
            String modelName,
            URI documentUri,
            String filename,
            double score,
            List<ModelVersionHit> versions) {
        this(uid, modelId, modelName, documentUri, filename, score, versions, null, List.of(), null, false, null, false);
    }
}

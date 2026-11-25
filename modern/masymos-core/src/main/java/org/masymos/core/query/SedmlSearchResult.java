package org.masymos.core.query;

import java.net.URI;
import java.util.List;

/**
 * One SED-ML description hit. {@code referencedModelUris} is the declared-or-resolved model source
 * of every {@code HAS_MODELREFERENCE} the description carries, so a description whose source cannot
 * be resolved still reports what it declared; the list is copied defensively.
 */
public record SedmlSearchResult(
        String uid,
        String fileId,
        String modelName,
        URI documentUri,
        String versionId,
        double score,
        List<URI> referencedModelUris
) {
    public SedmlSearchResult {
        referencedModelUris = referencedModelUris == null ? List.of() : List.copyOf(referencedModelUris);
    }
}

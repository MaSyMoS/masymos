package org.masymos.core.query.fusion;

import java.util.Objects;

/**
 * One provenance entry of a fused row: which ranker returned it, at which 1-based rank after
 * collation, and with which native score.
 */
public record FusionSource(Ranker ranker, int rank, double score) {
    public FusionSource {
        Objects.requireNonNull(ranker, "ranker");
    }
}

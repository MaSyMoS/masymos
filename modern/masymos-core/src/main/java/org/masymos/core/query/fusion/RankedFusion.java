package org.masymos.core.query.fusion;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * A strategy's output row. {@code fusedScore} is defined only on the strategy's own scale and is
 * {@code null} for an order-only strategy. {@code sources} carries the per-ranker provenance in
 * ranker order, held by a defensive copy.
 */
public record RankedFusion(
        String uid,
        String fileId,
        URI documentUri,
        Double fusedScore,
        List<FusionSource> sources
) {
    public RankedFusion {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(fileId, "fileId");
        sources = sources == null ? List.<FusionSource>of() : List.copyOf(sources);
    }
}

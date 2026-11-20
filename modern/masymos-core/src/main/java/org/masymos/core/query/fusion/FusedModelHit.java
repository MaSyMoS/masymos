package org.masymos.core.query.fusion;

import java.util.List;
import java.util.Objects;

/**
 * One hydrated fused row: the representative model version of a model file, the fused score of the
 * strategy that produced it, and the per-ranker provenance that led to it.
 *
 * <p>{@code fusedScore} is on the producing strategy's own scale only — {@code 0 < fusedScore <= 1}
 * for {@code bordaCombMnz} and {@code null} for an order-only strategy such as {@code bestRank}. It
 * is not comparable with per-entity scores, with legacy values, or across strategies or requests.
 * {@code sources} is held by a defensive copy.
 */
public record FusedModelHit(ModelRow row, Double fusedScore, List<FusionSource> sources) {
    public FusedModelHit {
        Objects.requireNonNull(row, "row");
        sources = sources == null ? List.<FusionSource>of() : List.copyOf(sources);
    }
}

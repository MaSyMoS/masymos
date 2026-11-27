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
 *
 * <p>{@code limits} says whether the request was cut short by a bound of {@code REL-007} before this
 * row was reached. It is a fact about the request, so every hit of one answer carries the same
 * value; it is carried per hit because the HTTP answer is a bare array of hits and a marker has to
 * live on a hit to reach a caller at all ({@code C03-2}). It is not {@code row.documentTruncated}:
 * that one describes the stored copy of a model's source document.
 */
public record FusedModelHit(ModelRow row, Double fusedScore, List<FusionSource> sources, FusionLimits limits) {
    public FusedModelHit {
        Objects.requireNonNull(row, "row");
        sources = sources == null ? List.<FusionSource>of() : List.copyOf(sources);
        limits = limits == null ? FusionLimits.NONE : limits;
    }
}

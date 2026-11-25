package org.masymos.core.query.fusion;

import java.util.List;

/**
 * A rank-fusion strategy. {@code producesScore()} says whether {@code fusedScore} is meaningful on
 * the strategy's own scale; when it is {@code false} the field is {@code null} and only the order
 * is defined.
 */
public interface RankFusionStrategy {
    /** The wire name of the strategy, unique among the registered strategies. */
    String id();

    /** A one-line human-readable description, shown by the strategy listing. */
    String description();

    /** Whether this strategy yields a score, or only an order. */
    boolean producesScore();

    /**
     * Fuses the already collated, non-empty rankers.
     *
     * <p>A returned row's {@code uid} must name a model row one of the {@code rankers} produced: the
     * caller hydrates rows by that uid, and a synthesised one is dropped without a marker
     * (`BORDA-002`, `C03-4`). A strategy that needs an identity of its own must derive it from a
     * ranker entry, not invent one.</p>
     *
     * @param rankers the rankers in a fixed order, each collated by {@link RankCollation}
     * @param weights the per-ranker weights, never {@code null}
     * @return the fused rows in result order
     */
    List<RankedFusion> fuse(List<RankedList> rankers, FusionWeights weights);
}

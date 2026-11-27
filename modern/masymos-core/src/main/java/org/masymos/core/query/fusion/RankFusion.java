package org.masymos.core.query.fusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The fusion engine: it restores the duplicate rule by collating every ranker, drops the rankers
 * that are empty afterwards, and delegates the collated, non-empty rankers to the strategy.
 *
 * <p>Dropping the empty rankers is what keeps the combMNZ divisor sane: {@code k} then counts only
 * the rankers that actually contributed, and a request whose rankers all came back empty yields an
 * empty list instead of a division by zero. The engine does not truncate; the caller cuts to its
 * {@code top} after the order is fixed.
 */
public final class RankFusion {

    /**
     * The deterministic order both strategies end with: fused score descending (nulls last, so it is
     * inert for the order-only strategy), then the number of rankers that returned the row
     * descending, then {@code documentUri} ascending with nulls last, then {@code uid} ascending.
     * Equal inputs therefore give equal order, which is what makes the service's cut to {@code top}
     * reproducible.
     */
    static final Comparator<RankedFusion> DETERMINISTIC_ORDER =
            Comparator.comparing(
                            RankedFusion::fusedScore, Comparator.nullsLast(Comparator.<Double>reverseOrder()))
                    .thenComparing(ranked -> ranked.sources().size(), Comparator.reverseOrder())
                    .thenComparing(RankedFusion::documentUri, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RankedFusion::uid);

    private RankFusion() {
    }

    /**
     * Collates and fuses the rankers.
     *
     * @param rankers the rankers in their fixed order; may be {@code null} or empty
     * @param strategy the strategy to delegate to
     * @param weights the per-ranker weights, never {@code null}
     * @return the fused rows in the strategy's order, or an empty list when every ranker is empty
     * @throws NullPointerException if {@code strategy} or {@code weights} is null
     */
    public static List<RankedFusion> fuse(
            List<RankedList> rankers, RankFusionStrategy strategy, FusionWeights weights) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(weights, "weights");

        List<RankedList> collated = new ArrayList<>(rankers == null ? 0 : rankers.size());
        if (rankers != null) {
            for (RankedList ranker : rankers) {
                if (ranker == null) {
                    continue;
                }
                RankedList result = RankCollation.collate(ranker);
                if (!result.hits().isEmpty()) {
                    collated.add(result);
                }
            }
        }
        if (collated.isEmpty()) {
            return List.of();
        }
        return strategy.fuse(List.copyOf(collated), weights);
    }
}

package org.masymos.core.query.fusion;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The score-producing strategy: a weighted Borda position per ranker, combined with the combMNZ
 * factor of how many rankers returned the row.
 *
 * <p>For a collated ranker {@code i} of size {@code n_i}, a row at rank {@code r_i} scores
 * {@code borda_i = 1 - (r_i - 1) / n_i}. With {@code h} the number of rankers that returned the
 * row, {@code k} the number of non-empty rankers and {@code W = sum w_i} over those rankers, the
 * fused score is
 *
 * <pre>
 * fused = ( sum_{i : row in R_i} w_i * borda_i ) * h / ( W * k )
 * </pre>
 *
 * <p>which lies in {@code (0, 1]}. This generalises legacy's {@code brn_sum * h / s^2} from four
 * fixed rankers of one size to {@code k} rankers of their own sizes. It is a generalisation, not a
 * port: the values are not comparable to legacy's, nor to the per-entity scores of the rankers.
 */
public final class BordaCombMnzStrategy implements RankFusionStrategy {

    /** The wire name of the strategy. */
    public static final String ID = "bordaCombMnz";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Weighted Borda position scaled by the combMNZ factor, on the strategy's own scale in "
                + "(0, 1] and not comparable to per-entity or legacy scores.";
    }

    @Override
    public boolean producesScore() {
        return true;
    }

    @Override
    public List<RankedFusion> fuse(List<RankedList> rankers, FusionWeights weights) {
        List<RankedList> nonEmpty = new ArrayList<>();
        if (rankers != null) {
            for (RankedList ranker : rankers) {
                if (ranker != null && !ranker.hits().isEmpty()) {
                    nonEmpty.add(ranker);
                }
            }
        }
        int rankerCount = nonEmpty.size();
        if (rankerCount == 0) {
            return List.of();
        }

        double totalWeight = 0.0;
        for (RankedList ranker : nonEmpty) {
            totalWeight += weights.weight(ranker.ranker());
        }

        Map<String, Accumulator> rows = new LinkedHashMap<>();
        for (RankedList ranker : nonEmpty) {
            List<RankedModel> hits = ranker.hits();
            int size = hits.size();
            double weight = weights.weight(ranker.ranker());
            for (int index = 0; index < size; index++) {
                RankedModel hit = hits.get(index);
                int rank = index + 1;
                double borda = 1.0 - ((rank - 1) / (double) size);
                rows.computeIfAbsent(hit.uid(), uid -> new Accumulator(hit))
                        .merge(ranker.ranker(), rank, hit.score(), weight * borda);
            }
        }

        List<RankedFusion> fused = new ArrayList<>(rows.size());
        for (Accumulator row : rows.values()) {
            int hits = row.sources.size();
            double score = row.weightedBorda * hits / (totalWeight * rankerCount);
            fused.add(new RankedFusion(row.uid, row.fileId, row.documentUri, score, row.sources));
        }
        fused.sort(RankFusion.DETERMINISTIC_ORDER);
        return fused;
    }

    /** The per-row accumulator: the identity of the first hit that introduced the row, and sources. */
    private static final class Accumulator {
        private final String uid;
        private final String fileId;
        private final URI documentUri;
        private final List<FusionSource> sources = new ArrayList<>();
        private double weightedBorda;

        private Accumulator(RankedModel first) {
            this.uid = first.uid();
            this.fileId = first.fileId();
            this.documentUri = first.documentUri();
        }

        private void merge(Ranker ranker, int rank, double nativeScore, double contribution) {
            sources.add(new FusionSource(ranker, rank, nativeScore));
            weightedBorda += contribution;
        }
    }
}

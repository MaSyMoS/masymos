package org.masymos.core.query.fusion;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The order-only strategy: rows are ordered by the best (smallest) rank over the rankers that
 * returned them, and every {@code fusedScore} is {@code null}.
 *
 * <p>A row missing from a ranker is not penalised with a fake rank: only the ranks of the rankers
 * that contain it count, which is the same as counting {@code +Infinity} for the others. Ties go to
 * the row returned by more rankers, then {@code documentUri} ascending (nulls last), then
 * {@code uid} ascending. Weights are accepted but have no effect; the order alone is defined.
 */
public final class BestRankStrategy implements RankFusionStrategy {

    /** The wire name of the strategy. */
    public static final String ID = "bestRank";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Orders rows by their best rank across the rankers without computing a score.";
    }

    @Override
    public boolean producesScore() {
        return false;
    }

    @Override
    public List<RankedFusion> fuse(List<RankedList> rankers, FusionWeights weights) {
        Map<String, Row> rows = new LinkedHashMap<>();
        if (rankers != null) {
            for (RankedList ranker : rankers) {
                if (ranker == null) {
                    continue;
                }
                List<RankedModel> hits = ranker.hits();
                for (int index = 0; index < hits.size(); index++) {
                    RankedModel hit = hits.get(index);
                    int rank = index + 1;
                    rows.computeIfAbsent(hit.uid(), uid -> new Row(hit))
                            .merge(ranker.ranker(), rank, hit.score());
                }
            }
        }

        List<RankedFusion> fused = new ArrayList<>(rows.size());
        for (Row row : rows.values()) {
            fused.add(new RankedFusion(row.uid, row.fileId, row.documentUri, null, row.sources));
        }
        fused.sort(Comparator.comparingInt(BestRankStrategy::bestRank)
                .thenComparing(RankFusion.DETERMINISTIC_ORDER));
        return fused;
    }

    private static int bestRank(RankedFusion ranked) {
        int best = Integer.MAX_VALUE;
        for (FusionSource source : ranked.sources()) {
            best = Math.min(best, source.rank());
        }
        return best;
    }

    /** The per-row accumulator: the identity of the first hit that introduced the row, and sources. */
    private static final class Row {
        private final String uid;
        private final String fileId;
        private final URI documentUri;
        private final List<FusionSource> sources = new ArrayList<>();

        private Row(RankedModel first) {
            this.uid = first.uid();
            this.fileId = first.fileId();
            this.documentUri = first.documentUri();
        }

        private void merge(Ranker ranker, int rank, double nativeScore) {
            sources.add(new FusionSource(ranker, rank, nativeScore));
        }
    }
}

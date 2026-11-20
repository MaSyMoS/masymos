package org.masymos.core.query.fusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The intra-ranker duplicate rule, ported from legacy's
 * {@code ResultSetUtil.collateModelResultSetByModelId} ({@code code/masymos-core/.../util/
 * ResultSetUtil.java:126-163}).
 *
 * <p>Hits sharing a {@code uid} are collapsed: their scores are summed, and every score of the
 * ranker is divided by the largest number of <em>extra</em> occurrences in that ranker, floored at
 * one. A model found twice is therefore not divided at all, one found three times is divided by
 * two. The result is sorted by score descending, and equal scores keep the caller's order — legacy
 * used {@code Collections.sort} with a score comparator only, which is exactly that stable sort.
 * The caller therefore owns the tie order, and this class must not invent one: ranking a caller's
 * order by {@code uid} handed a file's versions a random-UUID rank (QRY-11).
 */
public final class RankCollation {

    private RankCollation() {
    }

    /**
     * Collates one ranker.
     *
     * @param ranker the ranker to collate; an empty ranker collates to an empty ranker
     * @return a new ranker of the same {@link Ranker} identity with the collated, sorted hits
     * @throws NullPointerException if {@code ranker} is null
     */
    public static RankedList collate(RankedList ranker) {
        Objects.requireNonNull(ranker, "ranker");
        if (ranker.hits().isEmpty()) {
            return new RankedList(ranker.ranker(), List.of());
        }

        Map<String, RankedModel> kept = new HashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (RankedModel hit : ranker.hits()) {
            Integer seen = occurrences.get(hit.uid());
            if (seen == null) {
                order.add(hit.uid());
                occurrences.put(hit.uid(), 1);
                kept.put(hit.uid(), hit);
            } else {
                occurrences.put(hit.uid(), seen + 1);
                RankedModel previous = kept.get(hit.uid());
                kept.put(hit.uid(), new RankedModel(
                        previous.uid(), previous.fileId(), previous.documentUri(), previous.score() + hit.score()));
            }
        }

        int maxExtras = 1;
        for (String uid : order) {
            maxExtras = Math.max(maxExtras, occurrences.get(uid) - 1);
        }

        List<RankedModel> collated = new ArrayList<>(order.size());
        for (String uid : order) {
            RankedModel hit = kept.get(uid);
            collated.add(new RankedModel(hit.uid(), hit.fileId(), hit.documentUri(), hit.score() / maxExtras));
        }
        // Score descending only: List.sort is stable, so equal scores keep the caller's order and
        // the caller — never a random uid — decides the tie (QRY-11).
        collated.sort(Comparator.comparingDouble(RankedModel::score).reversed());
        return new RankedList(ranker.ranker(), collated);
    }
}

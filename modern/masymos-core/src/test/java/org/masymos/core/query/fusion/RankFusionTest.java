package org.masymos.core.query.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RankFusionTest {

    private final BordaCombMnzStrategy borda = new BordaCombMnzStrategy();
    private final BestRankStrategy bestRank = new BestRankStrategy();

    @Test
    void returnsAnEmptyListForAnEmptyInput() {
        assertEquals(List.of(), RankFusion.fuse(List.of(), borda, FusionWeights.none()));
        assertEquals(List.of(), RankFusion.fuse(List.of(), bestRank, FusionWeights.none()));
    }

    @Test
    void collatesDuplicatesBeforeTheStrategySeesThem() {
        // the strategy is handed this ranker, which fuses to exactly two rows that are now ranks 1-2
        var ranker = new RankedList(Ranker.MODEL, List.of(model("u1", 1.0), model("u1", 1.0), model("u2", 1.0)));

        var result = RankFusion.fuse(List.of(ranker), borda, FusionWeights.none());

        assertEquals(List.of("u1", "u2"), uids(result));
        assertEquals(List.of(1, 2), sourceRanks(result));
        assertEquals(1.0, result.get(0).fusedScore(), 1e-9);
        assertEquals(0.5, result.get(1).fusedScore(), 1e-9);

        // and the same input through the order-only strategy, which reports its sources verbatim:
        // the duplicate must already be collapsed there too, hence the ranks 1 and 2
        var orderOnly = RankFusion.fuse(List.of(ranker), bestRank, FusionWeights.none());
        assertEquals(List.of("u1", "u2"), uids(orderOnly));
        assertEquals(List.of(1, 2), sourceRanks(orderOnly));
    }

    @Test
    void dropsEmptyRankersSoKCountsOnlyNonEmptyOnes() {
        // two rankers, one empty: W=1 and k=1, so the survivor's rows score 1.0 and 0.5, exactly as
        // a single-ranker input would. If the empty ranker were counted, k=2 would halve both.
        var full = ranker(Ranker.MODEL, "u1", "u2");
        var empty = new RankedList(Ranker.ANNOTATION, List.of());

        var result = RankFusion.fuse(List.of(full, empty), borda, FusionWeights.none());

        assertEquals(List.of("u1", "u2"), uids(result));
        assertEquals(1.0, result.get(0).fusedScore(), 1e-9);
        assertEquals(0.5, result.get(1).fusedScore(), 1e-9);
        assertEquals(1, result.get(0).sources().size());
    }

    @Test
    void anEmptyWeightOfADroppedRankerDoesNotEnterW() {
        // the annotation ranker is empty, so its weight of 5 must not inflate W: W stays 3 and k is 1
        var weights = new FusionWeights(Map.of(Ranker.MODEL, 3.0, Ranker.ANNOTATION, 5.0));

        var result = RankFusion.fuse(
                List.of(ranker(Ranker.MODEL, "u1", "u2"), new RankedList(Ranker.ANNOTATION, List.of())),
                borda,
                weights);

        assertEquals(1.0, result.get(0).fusedScore(), 1e-9);
        assertEquals(0.5, result.get(1).fusedScore(), 1e-9);
    }

    @Test
    void returnsAnEmptyListWhenEveryRankerIsEmpty() {
        var result = RankFusion.fuse(
                List.of(new RankedList(Ranker.MODEL, List.of()), new RankedList(Ranker.PERSON, List.of())),
                borda,
                FusionWeights.none());

        assertEquals(List.of(), result);
    }

    @Test
    void twoIdenticalCallsReturnIdenticalOrder() {
        var rankers = List.<RankedList>of(
                ranker(Ranker.MODEL, "u3", "u1", "u2"),
                ranker(Ranker.ANNOTATION, "u2", "u3"),
                ranker(Ranker.PERSON, "u1"));

        var first = RankFusion.fuse(rankers, borda, FusionWeights.none());
        var second = RankFusion.fuse(rankers, borda, FusionWeights.none());
        var firstOrderOnly = RankFusion.fuse(rankers, bestRank, FusionWeights.none());
        var secondOrderOnly = RankFusion.fuse(rankers, bestRank, FusionWeights.none());

        assertEquals(first, second);
        assertEquals(firstOrderOnly, secondOrderOnly);
        assertEquals(uids(first), uids(second));
        assertEquals(uids(firstOrderOnly), uids(secondOrderOnly));
        assertEquals(first.size(), firstOrderOnly.size());
        assertTrue(first.stream().allMatch(row -> row.fusedScore() != null));
        assertTrue(firstOrderOnly.stream().allMatch(row -> row.fusedScore() == null));
    }

    /** One ranker whose hits carry descending native scores, so the input order is the rank order. */
    private static RankedList ranker(Ranker ranker, String... uids) {
        List<RankedModel> hits = new ArrayList<>();
        for (int index = 0; index < uids.length; index++) {
            String uid = uids[index];
            hits.add(new RankedModel(uid, "file-" + uid, URI.create("urn:masymos:file-" + uid),
                    uids.length - index));
        }
        return new RankedList(ranker, hits);
    }

    private static RankedModel model(String uid, double score) {
        return new RankedModel(uid, "file-" + uid, URI.create("urn:masymos:file-" + uid), score);
    }

    private static List<String> uids(List<RankedFusion> rows) {
        return rows.stream().map(RankedFusion::uid).toList();
    }

    private static List<Integer> sourceRanks(List<RankedFusion> rows) {
        return rows.stream().map(row -> row.sources().getFirst().rank()).toList();
    }
}

package org.masymos.core.query.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BordaCombMnzStrategyTest {

    private final BordaCombMnzStrategy strategy = new BordaCombMnzStrategy();

    @Test
    void identifiesItselfAsAScoreProducingStrategy() {
        assertEquals("bordaCombMnz", BordaCombMnzStrategy.ID);
        assertEquals(BordaCombMnzStrategy.ID, strategy.id());
        assertTrue(strategy.producesScore());
    }

    @Test
    void scoresASingleRankerAsTheNormalisedBordaValue() {
        // one ranker of two: rank 1 -> 1.0, rank 2 -> 0.5
        var result = strategy.fuse(List.of(ranker(Ranker.MODEL, "u1", "u2")), FusionWeights.none());

        assertEquals(List.of("u1", "u2"), uids(result));
        assertEquals(1.0, result.get(0).fusedScore(), 1e-9);
        assertEquals(0.5, result.get(1).fusedScore(), 1e-9);
    }

    @Test
    void rewardsHitsFoundBySeveralRankers() {
        // u1 is rank 1 in both rankers: h=2, k=2, W=2 -> (1 + 1) * 2 / (2 * 2) = 1.0
        // u2 is rank 2 of the two-hit model ranker only: h=1, k=2 -> 0.5 * 1 / (2 * 2) = 0.125
        var result = strategy.fuse(
                List.of(ranker(Ranker.MODEL, "u1", "u2"), ranker(Ranker.ANNOTATION, "u1", "u3")),
                FusionWeights.none());

        var byUid = byUid(result);
        assertEquals(1.0, byUid.get("u1").fusedScore(), 1e-9);
        assertEquals(0.125, byUid.get("u2").fusedScore(), 1e-9);
        // u3 is rank 2 of the two-hit annotation ranker, found by one of two rankers:
        // 0.5 * 1 / (2 * 2) = 0.125, the same as u2, so the tie-breaks decide
        assertEquals(0.125, byUid.get("u3").fusedScore(), 1e-9);
        assertEquals(List.of("u1", "u2", "u3"), uids(result));
    }

    @Test
    void appliesPerRankerWeightsToTheContribution() {
        // weights MODEL=3, ANNOTATION=1; W=4, k=2
        // u1 in both at rank 1 -> (3*1 + 1*1) * 2 / (4*2) = 1.0
        // u2 is rank 2 of the two-hit annotation ranker -> (1*0.5) * 1 / (4*2) = 0.0625
        var weights = new FusionWeights(Map.of(Ranker.MODEL, 3.0, Ranker.ANNOTATION, 1.0));

        var result = strategy.fuse(
                List.of(ranker(Ranker.MODEL, "u1", "u3"), ranker(Ranker.ANNOTATION, "u1", "u2")), weights);

        var byUid = byUid(result);
        assertEquals(1.0, byUid.get("u1").fusedScore(), 1e-9);
        assertEquals(0.0625, byUid.get("u2").fusedScore(), 1e-9);
    }

    @Test
    void ordersEqualScoresBySourceCountThenDocumentUriThenUid() {
        // (a) source count: "x" is rank 1 in two rankers, "aa" and "ab" are rank 1 in one ranker
        // each. Every ranker below is single-hit, so borda is 1.0 everywhere and h separates them.
        // k=4, W=4: "x" has h=2 -> (1+1)*2/(4*4) = 0.25; "aa"/"ab" have h=1 -> 1*1/16 = 0.0625
        var sourceCount = List.of(
                new RankedList(Ranker.MODEL, List.of(hit("x", "urn:masymos:x", 1.0))),
                new RankedList(Ranker.ANNOTATION, List.of(hit("x", "urn:masymos:x", 1.0))),
                new RankedList(Ranker.PERSON, List.of(hit("aa", "urn:masymos:aa", 1.0))),
                new RankedList(Ranker.PUBLICATION, List.of(hit("ab", "urn:masymos:ab", 1.0))));
        var bySourceCount = strategy.fuse(sourceCount, FusionWeights.none());
        assertEquals("x", bySourceCount.get(0).uid());
        assertEquals(0.25, bySourceCount.get(0).fusedScore(), 1e-9);
        assertEquals(0.0625, bySourceCount.get(1).fusedScore(), 1e-9);
        assertEquals(0.0625, bySourceCount.get(2).fusedScore(), 1e-9);

        // (b) documentUri decides between rows that tie on score and on h. Here "a"/"b" agree on uid
        // and on documentUri order, so the assertion also pins that documentUri is preferred over the
        // input order ("b" is given first); (c) pins uid as the very last key.
        var byUri = strategy.fuse(
                List.of(
                        new RankedList(Ranker.MODEL, List.of(hit("b", "urn:masymos:b", 1.0))),
                        new RankedList(Ranker.ANNOTATION, List.of(hit("a", "urn:masymos:a", 1.0)))),
                FusionWeights.none());
        assertEquals(List.of("a", "b"), uids(byUri));

        // (c) a null documentUri sorts last, and only then does uid decide
        var byUid = strategy.fuse(
                List.of(
                        new RankedList(Ranker.MODEL, List.of(hit("z", null, 1.0))),
                        new RankedList(Ranker.ANNOTATION, List.of(hit("y", null, 1.0))),
                        new RankedList(Ranker.PERSON, List.of(hit("x", "urn:masymos:x", 1.0)))),
                FusionWeights.none());
        assertEquals(List.of("x", "y", "z"), uids(byUid));
    }

    @Test
    void reportsTheSourcesOfEveryRankerThatReturnedTheRow() {
        // the same uid in two rankers, at different ranks with different native scores
        var rankers = List.of(ranker(Ranker.MODEL, "u1", "u2"), ranker(Ranker.ANNOTATION, "u2", "u1"));

        var result = strategy.fuse(rankers, FusionWeights.none());

        var row = byUid(result).get("u1");
        assertEquals(2, row.sources().size());
        assertEquals(new FusionSource(Ranker.MODEL, 1, 2.0), row.sources().get(0));
        assertEquals(new FusionSource(Ranker.ANNOTATION, 2, 1.0), row.sources().get(1));
        // and the row carries the identity of the hit that introduced it
        assertEquals("file-u1", row.fileId());
        assertEquals(URI.create("urn:masymos:file-u1"), row.documentUri());
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

    private static RankedModel hit(String uid, String documentUri, double score) {
        return new RankedModel(uid, "file-" + uid, documentUri == null ? null : URI.create(documentUri), score);
    }

    private static List<String> uids(List<RankedFusion> rows) {
        return rows.stream().map(RankedFusion::uid).toList();
    }

    private static Map<String, RankedFusion> byUid(List<RankedFusion> rows) {
        Map<String, RankedFusion> byUid = new LinkedHashMap<>();
        for (RankedFusion row : rows) {
            byUid.put(row.uid(), row);
        }
        return byUid;
    }
}

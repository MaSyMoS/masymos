package org.masymos.core.query.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BestRankStrategyTest {

    private final BestRankStrategy strategy = new BestRankStrategy();

    @Test
    void identifiesItselfAsAnOrderOnlyStrategy() {
        assertEquals("bestRank", BestRankStrategy.ID);
        assertEquals(BestRankStrategy.ID, strategy.id());
        assertFalse(strategy.producesScore());
    }

    @Test
    void ordersByTheBestRankAcrossTheRankers() {
        // u1 is rank 2 in the model ranker and rank 1 in the annotation ranker -> best 1
        // u2 is rank 1 in the model ranker -> best 1, but found by one ranker only
        // u3 is rank 3 in the model ranker -> best 3
        // order: best rank, then the rows sharing rank 1 fall back to the source count
        var result = strategy.fuse(
                List.of(ranker(Ranker.MODEL, "u2", "u1", "u3"), ranker(Ranker.ANNOTATION, "u1")),
                FusionWeights.none());

        assertEquals(List.of("u1", "u2", "u3"), uids(result));
    }

    @Test
    void doesNotPenaliseARowMissingFromARankerWithAFakeRank() {
        // u1 is rank 1 of three in the model ranker and absent from the annotation ranker.
        // u2 is rank 2 of three in the model ranker. Missing from a ranker counts as +Infinity, so
        // the absent ranker cannot push u1 behind u2 while u2 is itself ranked.
        var result = strategy.fuse(
                List.of(ranker(Ranker.MODEL, "u1", "u2", "u3"), ranker(Ranker.ANNOTATION, "x1", "x2")),
                FusionWeights.none());

        var byUid = uids(result);
        assertTrue(byUid.indexOf("u1") < byUid.indexOf("u2"), "u1 (rank 1) must precede u2 (rank 2)");
        assertTrue(byUid.indexOf("u2") < byUid.indexOf("u3"), "u2 (rank 2) must precede u3 (rank 3)");
        // u1 has best rank 1 and is found by one ranker; x1, x2 are annotation-only rank 1 and 2.
        // So u1 -> x1 (both rank 1, u1 shares it with no one, x1 succeeds on the input order's uid
        // comparison), then the rank-2 rows by source count and documentUri, then u3 at rank 3.
        // x2 is rank 2 as well, and u2/carry one source each, so documentUri decides:
        // "file-u2" < "urn:masymos:file-x2"
        assertEquals(List.of("u1", "x1", "u2", "x2", "u3"), byUid);
    }

    @Test
    void breaksTiesByRankerCountThenDocumentUriWithNullsLastThenUid() {
        // every row below is rank 1 of its own single-hit ranker, so rank is 1 everywhere:
        // the source count decides first, then documentUri (nulls last), then uid.
        // The four ranker slots are reused in two groups, which is the only way to get four rankers
        // each with one hit.
        var result = strategy.fuse(
                List.of(
                        new RankedList(Ranker.MODEL, List.of(hit("both", "urn:masymos:both", 1.0))),
                        new RankedList(Ranker.ANNOTATION, List.of(hit("both", "urn:masymos:both", 1.0))),
                        new RankedList(Ranker.PERSON, List.of(hit("uriB", "urn:masymos:b", 1.0)))),
                FusionWeights.none());
        var withUri = strategy.fuse(
                List.of(
                        new RankedList(Ranker.MODEL, List.of(hit("uriB", "urn:masymos:b", 1.0))),
                        new RankedList(Ranker.ANNOTATION, List.of(hit("uriA", "urn:masymos:a", 1.0))),
                        new RankedList(Ranker.PERSON, List.of(hit("noUri", null, 1.0)))),
                FusionWeights.none());

        // "both" h=2 first; then the h=1 rows by documentUri ("urn:masymos:a" < "urn:masymos:b"),
        // and the row without a documentUri last
        assertEquals(List.of("both", "uriB"), uids(result));
        assertEquals(List.of("uriA", "uriB", "noUri"), uids(withUri));
    }

    @Test
    void breaksEqualRankAndEqualSourceCountByUidAscending() {
        // two rows with no documentUri, both rank 1 of a single-hit ranker
        var result = strategy.fuse(
                List.of(
                        new RankedList(Ranker.MODEL, List.of(hit("z", null, 5.0))),
                        new RankedList(Ranker.ANNOTATION, List.of(hit("y", null, 9.0)))),
                FusionWeights.none());

        assertEquals(List.of("y", "z"), uids(result));
    }

    @Test
    void neverReportsAFusedScore() {
        var result = strategy.fuse(
                List.of(ranker(Ranker.MODEL, "u1", "u2"), ranker(Ranker.PERSON, "u2")), FusionWeights.none());

        assertFalse(result.isEmpty());
        for (RankedFusion row : result) {
            assertNull(row.fusedScore(), "bestRank must not report a score for " + row.uid());
        }
    }

    @Test
    void reportsTheSourcesInRankerOrderAndIgnoresTheWeights() {
        var rankers = List.of(ranker(Ranker.MODEL, "u1", "u2"), ranker(Ranker.ANNOTATION, "u2", "u1"));

        var weighted = strategy.fuse(rankers, new FusionWeights(Map.of(Ranker.ANNOTATION, 99.0)));
        var plain = strategy.fuse(rankers, FusionWeights.none());

        assertEquals(plain, weighted);
        var u2 = weighted.stream().filter(row -> row.uid().equals("u2")).findFirst().orElseThrow();
        assertEquals(List.of(
                new FusionSource(Ranker.MODEL, 2, 1.0), new FusionSource(Ranker.ANNOTATION, 1, 2.0)),
                u2.sources());
    }

    @Test
    void returnsAnEmptyListForAnEmptyRankerList() {
        assertEquals(List.of(), strategy.fuse(List.of(), FusionWeights.none()));
        assertEquals(List.of(), strategy.fuse(List.of(new RankedList(Ranker.MODEL, List.of())),
                FusionWeights.none()));
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
}

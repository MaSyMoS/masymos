package org.masymos.core.query.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankCollationTest {

    @Test
    void collapsesDuplicatesBySummingAndDividingByExtraOccurrences() {
        // u1 appears twice (one extra occurrence) -> divisor 1, so no division
        var ranker = new RankedList(Ranker.MODEL, List.of(
                model("u1", 1.0), model("u2", 2.0), model("u1", 3.0)));

        var collated = RankCollation.collate(ranker);

        assertEquals(List.of("u1", "u2"), uids(collated));
        assertEquals(4.0, collated.hits().getFirst().score(), 1e-9);
    }

    @Test
    void dividesEveryScoreByTheExtraOccurrenceCountOfTheWorstDuplicate() {
        // u1 appears three times (two extras) -> divisor 2, applied to the whole ranker as legacy
        // did: u1 = 3.0/2 = 1.5, u2 = 2.0/2 = 1.0
        var ranker = new RankedList(Ranker.MODEL, List.of(
                model("u1", 1.0), model("u1", 1.0), model("u1", 1.0), model("u2", 2.0)));

        var collated = RankCollation.collate(ranker);

        assertEquals(List.of("u1", "u2"), uids(collated));
        assertEquals(1.5, collated.hits().getFirst().score(), 1e-9);
        assertEquals(1.0, collated.hits().get(1).score(), 1e-9);
    }

    @Test
    void keepsTheRankerIdentityAndLeavesASingleHitUntouched() {
        var ranker = new RankedList(Ranker.PERSON, List.of(model("u1", 7.5)));

        var collated = RankCollation.collate(ranker);

        assertEquals(Ranker.PERSON, collated.ranker());
        assertEquals(1, collated.hits().size());
        assertEquals("u1", collated.hits().getFirst().uid());
        assertEquals(7.5, collated.hits().getFirst().score(), 1e-9);
    }

    /**
     * QRY-11: equal scores keep the caller's order. The caller ranks its hits for a reason — the
     * service hands in the model ranker's order, so a file-wide hit credits the file's best-scoring
     * version first. Ties broken by {@code uid} meant a random UUID decided that instead.
     */
    @Test
    void keepsTheCallersOrderOfEqualScores() {
        var ranker = new RankedList(Ranker.MODEL, List.of(
                model("u3", 1.0), model("u1", 1.0), model("u2", 1.0)));

        var collated = RankCollation.collate(ranker);

        assertEquals(List.of("u3", "u1", "u2"), uids(collated));
    }

    @Test
    void collatesAnEmptyRankerToItself() {
        var collated = RankCollation.collate(new RankedList(Ranker.ANNOTATION, List.of()));

        assertEquals(Ranker.ANNOTATION, collated.ranker());
        assertEquals(List.of(), collated.hits());
    }

    /**
     * The surviving row keeps the identity of the occurrence that introduced its {@code uid} — the
     * file and document URI of the first one — while the score is the sum over every occurrence.
     * Both fields are a function of the version, so this is invisible on a consistent graph; the rule
     * is pinned so an inconsistent row set cannot silently adopt a later occurrence's identity and
     * report a summed score under a file no ranker returned.
     */
    @Test
    void keepsTheFirstOccurrencesIdentityWhenSummingScores() {
        var ranker = new RankedList(Ranker.ANNOTATION, List.of(
                model("u1", "f1", URI.create("https://e.org/a"), 0.5),
                model("u1", "f2", URI.create("https://e.org/b"), 0.7)));

        var collated = RankCollation.collate(ranker);

        assertEquals(1, collated.hits().size());
        RankedModel kept = collated.hits().getFirst();
        assertEquals("u1", kept.uid());
        assertEquals("f1", kept.fileId());
        assertEquals(URI.create("https://e.org/a"), kept.documentUri());
        assertEquals(1.2, kept.score(), 1e-9);
    }

    private static RankedModel model(String uid, double score) {
        return model(uid, "file-" + uid, URI.create("urn:masymos:file-" + uid), score);
    }

    private static RankedModel model(String uid, String fileId, URI documentUri, double score) {
        return new RankedModel(uid, fileId, documentUri, score);
    }

    private static List<String> uids(RankedList ranker) {
        return ranker.hits().stream().map(RankedModel::uid).toList();
    }
}

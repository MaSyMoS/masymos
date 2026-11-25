package org.masymos.core.query.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelDeletionResult;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelVersion;
import org.masymos.core.model.StoredDocument;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.PersonSearchResult;
import org.masymos.core.query.PersonSummary;
import org.masymos.core.query.PublicationSearchResult;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ModelRowLookup;
import org.masymos.core.spi.ResourceQueryRepository;

class RankFusionQueryServiceTest {

    private static final String MODEL_FILE = "BIOMD0001.xml";
    private static final String OTHER_FILE = "BIOMD0002.xml";
    private static final URI MODEL_URI = URI.create("https://example.org/BIOMD0001.xml");
    private static final URI OTHER_URI = URI.create("https://example.org/BIOMD0002.xml");
    private static final URI ANNOTATION_MODEL_URI = URI.create("https://example.org/ANNOTATED.xml");

    @Test
    void passesKeywordAndModelClausesToTheModelRankerOnly() {
        var models = new FakeModelRepository();
        var resources = new FakeResourceQueryRepository();
        var service = service(models, resources, new FakeModelRowLookup());

        service.query(query("gly", List.of(clause(QueryField.NAME, "gly")), 10));

        assertEquals("gly", models.requests.getFirst().keyword());
        assertEquals(1, models.requests.getFirst().clauses().size());
        assertEquals(QueryField.NAME, models.requests.getFirst().clauses().getFirst().field());

        for (ModelQueryRequest request : resources.allRequests()) {
            assertEquals(0, request.clauses().size());
            assertEquals("gly", request.keyword());
        }
        assertEquals(3, resources.allRequests().size());
    }

    @Test
    void skipsResourceQueriesWhenNoFreeTextTermIsLeft() {
        var models = new FakeModelRepository();
        var resources = new FakeResourceQueryRepository();
        var service = service(models, resources, new FakeModelRowLookup());

        service.query(query(null, List.of(clause(QueryField.NAME, "gly")), 10));

        assertEquals(1, models.requests.size());
        assertEquals(1, models.requests.getFirst().clauses().size());
        assertFalse(resources.called, "the resource repositories must not be called without a term");
    }

    @Test
    void collapsesTwoVersionsOfOneFileIntoOneRow() {
        var models = new FakeModelRepository();
        // the model query reports the version and the document facts, as QRY-2 made it do
        models.results = List.of(
                modelHit("u1", MODEL_FILE, MODEL_URI, 0.9, "v2"), modelHit("u2", MODEL_FILE, MODEL_URI, 0.4, "v1"));
        var lookup = new FakeModelRowLookup();
        var resources = new FakeResourceQueryRepository();
        resources.annotations = List.of(annotation(2.0, MODEL_URI));
        lookup.rows = List.of(
                new ModelRow("u1", MODEL_FILE, "m1", "one", MODEL_URI, "v2", true, 100L, false),
                new ModelRow("u2", MODEL_FILE, "m1", "one", MODEL_URI, "v1", true, 90L, false));
        var service = service(models, resources, lookup);

        List<FusedModelHit> hits = service.query(query("gly", List.of(), 10));

        assertEquals(1, hits.size());
        assertEquals(MODEL_FILE, hits.getFirst().row().fileId());
        assertEquals("u1", hits.getFirst().row().uid(), "u1 has the better fused score");
        assertEquals("v2", hits.getFirst().row().versionId(), "the representative version is hydrated");
    }

    @Test
    void expandsAResourceHitIntoEveryModelItResolvesTo() {
        var models = new FakeModelRepository();
        models.results = List.of();
        var lookup = new FakeModelRowLookup();
        URI shared = URI.create("https://example.org/SHARED.xml");
        lookup.rows = List.of(
                new ModelRow("u1", MODEL_FILE, "m1", "one", shared, "v1", true, 100L, false),
                new ModelRow("u2", OTHER_FILE, "m2", "two", shared, "v1", true, 100L, false));
        var resources = new FakeResourceQueryRepository();
        resources.annotations = List.of(annotation(1.5, shared));
        var service = service(models, resources, lookup);

        List<FusedModelHit> hits = service.query(query("gly", List.of(), 10));

        assertEquals(2, hits.size(), "one annotation hit raises both models of the same document");
        assertEquals("v1", hits.getFirst().row().versionId(), "a row known only through the lookup is hydrated from it");
        List<Integer> ranks = new ArrayList<>();
        for (FusedModelHit hit : hits) {
            List<FusionSource> annotationSources = hit.sources().stream()
                    .filter(source -> source.ranker() == Ranker.ANNOTATION)
                    .toList();
            assertEquals(1, annotationSources.size(), "each model carries its own annotation source");
            assertEquals(1.5, annotationSources.getFirst().score(), 1e-9);
            ranks.add(annotationSources.getFirst().rank());
        }
        // one hit that resolves to two models occupies consecutive ranks in its ranker, ordered by uid
        assertEquals(List.of(1, 2), ranks);
    }

    /**
     * QRY-11: one file-wide resource hit (D9) resolves to several versions of the same file. The
     * ranks it expands into must follow the model ranker, so the file's best-scoring version leads
     * that block — and then also holds the best fused score and represents the file. Ordering the
     * block by {@code uid} let a random UUID decide the representative: measured 2026-11-14, the
     * container test covering this composition passed and failed on alternating runs of the same
     * corpus, decided only by which document UUID sorted first.
     */
    @Test
    void creditsTheBestScoringVersionFirstWhenOneResourceHitResolvesToSeveralVersions() {
        var models = new FakeModelRepository();
        // uid ascending is the reverse of the model order: u1 is the weaker version of the file
        models.results = List.of(
                modelHit("u3", OTHER_FILE, OTHER_URI, 0.9, "v1"),
                modelHit("u2", MODEL_FILE, MODEL_URI, 0.4, "v1"),
                modelHit("u1", MODEL_FILE, MODEL_URI, 0.1, "v2"));
        var lookup = new FakeModelRowLookup();
        lookup.rows = List.of(
                new ModelRow("u1", MODEL_FILE, "m1", "one", MODEL_URI, "v2", true, 100L, false),
                new ModelRow("u2", MODEL_FILE, "m1", "one", MODEL_URI, "v1", true, 100L, false));
        var resources = new FakeResourceQueryRepository();
        resources.annotations = List.of(annotation(1.0, MODEL_URI));
        var service = service(models, resources, lookup);

        List<FusedModelHit> hits = service.query(query("gly", List.of(), 10));

        var file = hits.stream()
                .filter(hit -> MODEL_FILE.equals(hit.row().fileId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + MODEL_FILE + " in " + hits));
        assertEquals("u2", file.row().uid(), "the best-scoring version of the file represents it: " + hits);
        assertEquals("v1", file.row().versionId(), "and that version is named: " + hits);
        var annotationRank = file.sources().stream()
                .filter(source -> source.ranker() == Ranker.ANNOTATION)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no annotation source on " + file))
                .rank();
        assertEquals(1, annotationRank, "the file-wide hit credits the best-scoring version first: " + hits);
    }

    @Test
    void countsFilesNotVersionsForTop() {
        var models = new FakeModelRepository();
        models.results = List.of(
                modelHit("u1", MODEL_FILE, MODEL_URI, 0.9),
                modelHit("u2", MODEL_FILE, MODEL_URI, 0.8),
                modelHit("u3", OTHER_FILE, OTHER_URI, 0.7));
        var service = service(models, new FakeResourceQueryRepository(), new FakeModelRowLookup());

        List<FusedModelHit> hits = service.query(query("gly", List.of(), 1));

        assertEquals(1, hits.size());
        assertEquals(MODEL_FILE, hits.getFirst().row().fileId());
    }

    @Test
    void ignoresResourceHitsWithoutAResolvableModel() {
        var models = new FakeModelRepository();
        models.results = List.of(modelHit("u1", MODEL_FILE, MODEL_URI, 0.9));
        var resources = new FakeResourceQueryRepository();
        resources.annotations = List.of(annotation(2.0, ANNOTATION_MODEL_URI));
        resources.persons = List.of(person(2.0, ANNOTATION_MODEL_URI));
        resources.publications = List.of(publication(2.0, ANNOTATION_MODEL_URI));
        var service = service(models, resources, new FakeModelRowLookup());

        List<FusedModelHit> hits = service.query(query("gly", List.of(), 10));

        assertEquals(1, hits.size());
        assertEquals("u1", hits.getFirst().row().uid());
        assertEquals(List.of(Ranker.MODEL), hits.getFirst().sources().stream().map(FusionSource::ranker).toList());
    }

    @Test
    void keepsAModelHitWhoseFileNameIsNullOrMissing() {
        var models = new FakeModelRepository();
        models.results = List.of(new ModelSearchResult("u1", "m1", "one", MODEL_URI, null, 0.9));
        var service = service(models, new FakeResourceQueryRepository(), new FakeModelRowLookup());

        List<FusedModelHit> hits = service.query(query("gly", List.of(), 10));

        assertEquals(1, hits.size());
        assertEquals("u1", hits.getFirst().row().uid());
        assertEquals("u1", hits.getFirst().row().fileId(), "a hit without a filename is grouped under its uid");
        assertEquals(List.of(Ranker.MODEL), hits.getFirst().sources().stream().map(FusionSource::ranker).toList());
    }

    @Test
    void rejectsAnUnknownStrategyNamingTheKnownIds() {
        var service = new RankFusionQueryService(
                new FakeModelRepository(),
                new FakeResourceQueryRepository(),
                new FakeModelRowLookup(),
                List.of(new BestRankStrategy(), new BordaCombMnzStrategy()));

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.query(new AggregatedQuery("gly", List.of(), 10, null, "nope", FusionWeights.none())));

        assertTrue(exception.getMessage().contains("bordaCombMnz"), exception.getMessage());
        assertTrue(exception.getMessage().contains("bestRank"), exception.getMessage());
        assertEquals(
                List.of("bestRank", "bordaCombMnz"),
                service.strategies().stream().map(RankFusionStrategy::id).toList());
    }

    @Test
    void returnsAnEmptyListWhenEveryRankerIsEmpty() {
        var models = new FakeModelRepository();
        models.results = List.of();
        var service = service(models, new FakeResourceQueryRepository(), new FakeModelRowLookup());

        List<FusedModelHit> hits = service.query(query("gly", List.of(), 10));

        assertEquals(List.of(), hits);
    }

    @Test
    void probesOneRowBeyondThePerRankerDepth() {
        var models = new FakeModelRepository();
        var resources = new FakeResourceQueryRepository();
        var service = service(models, resources, new FakeModelRowLookup());

        service.query(query("gly", List.of(), 1000));

        assertEquals(201, models.requests.getFirst().top(), "the depth is 200 and the probe asks for one more");
        assertEquals(3, resources.allRequests().size());
        for (ModelQueryRequest request : resources.allRequests()) {
            assertEquals(201, request.top(), "every ranker is probed, not only the model ranker");
        }

        service.query(query("gly", List.of(), 1));

        assertEquals(26, models.requests.get(1).top(), "the floor is 25, plus the one probe row");
        assertNotNull(models.requests.get(1).keyword());
    }

    @Test
    void returnsANullFusedScoreForTheOrderOnlyStrategy() {
        var models = new FakeModelRepository();
        models.results = List.of(modelHit("u1", MODEL_FILE, MODEL_URI, 0.9));
        var service = service(
                models, new FakeResourceQueryRepository(), new FakeModelRowLookup(), List.of(new BestRankStrategy()));

        List<FusedModelHit> hits =
                service.query(new AggregatedQuery("gly", List.of(), 10, null, BestRankStrategy.ID, null));

        assertEquals(1, hits.size());
        assertNull(hits.getFirst().fusedScore());
    }

    @Test
    void keepsTheStrategyOrderWhenScoresAreMissing() {
        // file "z.xml" is found at model rank 2 and annotation rank 1 (best rank 1, two rankers);
        // file "a.xml" is found at model rank 1 only (best rank 1, one ranker). bestRank puts the
        // first file ahead; re-sorting the collapsed files by documentUri would put "a.xml" first.
        var models = new FakeModelRepository();
        models.results = List.of(
                modelHit("uB", "a.xml", URI.create("urn:masymos:a"), 0.9, "v1"),
                modelHit("uA", "z.xml", URI.create("urn:masymos:z"), 0.5, "v1"));
        var lookup = new FakeModelRowLookup();
        lookup.rows = List.of(new ModelRow("uA", "z.xml", "mA", "A", URI.create("urn:masymos:z"), "v1", false, null, false));
        var resources = new FakeResourceQueryRepository();
        resources.annotations = List.of(annotation(1.0, URI.create("urn:masymos:z")));
        var service = service(models, resources, lookup, List.of(new BestRankStrategy()));

        List<FusedModelHit> hits =
                service.query(new AggregatedQuery("gly", List.of(), 10, null, BestRankStrategy.ID, null));

        assertEquals(List.of("z.xml", "a.xml"), hits.stream().map(hit -> hit.row().fileId()).toList());
        assertNull(hits.getFirst().fusedScore());
    }

    /**
     * {@code C03-2}, found by review: filling the budget exactly is <em>not</em> a cut. 500 distinct
     * URIs are all asked for, so nothing was left out and the answer says nothing - the flag needs a
     * URI this ranker would have contributed and could not. Without this case the marker was `true` on
     * a complete answer, which is the false positive the row exists to prevent.
     */
    @Test
    void anExactlyFullUriBudgetIsNotACut() {
        var models = new FakeModelRepository();
        models.results = List.of();
        var resources = new FakeResourceQueryRepository();
        var lookup = new FakeModelRowLookup();
        resources.annotations = annotationsNaming(500);
        lookup.rows = rows(0, 500);
        var service = service(models, resources, lookup);

        var fused = service.query(query("gly", List.of(), 40));

        assertEquals(RankFusionQueryService.MAX_DOCUMENT_URIS_PER_RANKER, lookup.lastUris.size(),
                "all 500 URIs are asked for");
        assertEquals(40, fused.size(), "forty files at top: " + fused.size());
        assertFalse(fused.stream().anyMatch(hit -> hit.limits().anyTruncated()),
                "nothing was left out, so nothing may be reported: " + fused);
    }

    /**
     * {@code REL-007}: the resource-to-row resolution is bounded. 200 annotation hits - exactly the
     * per-ranker depth - naming four model files each are 800 distinct document URIs, and a hit is
     * file-wide, which is how one ranker exceeds the budget without exceeding its depth. At most
     * {@code MAX_DOCUMENT_URIS_PER_RANKER} of them reach the lookup, taken in rank order, so the cut is
     * deterministic. Asserted on the number of URIs the fake lookup received, not on query text.
     */
    @Test
    void capsTheDocumentUrisPassedToTheRowLookup() {
        var models = new FakeModelRepository();
        var resources = new FakeResourceQueryRepository();
        var lookup = new FakeModelRowLookup();
        resources.annotations = annotationsNaming(800);
        var service = service(models, resources, lookup);

        service.query(query("gly", List.of(), 40));

        assertEquals(RankFusionQueryService.MAX_DOCUMENT_URIS_PER_RANKER, lookup.lastUris.size(),
                "800 distinct URIs are cut to the per-ranker cap");
        assertEquals(URI.create("urn:masymos:file-0"), lookup.lastUris.getFirst(),
                "the highest-scoring hit's URI is kept, so the cut is in rank order");
    }

    /**
     * {@code C03-2} / {@code REL-007}: a capped URI set is <em>reported</em>. The same 800 URIs as above,
     * but the lookup resolves the 500 URIs it received to one file each, so the answer is a real fused
     * list - forty files at {@code top} - and every hit names the cut. This is the whole point of the
     * marker: without it this answer is identical to one from a store that simply holds 500 models, so a
     * caller cannot tell a shortened list from a complete one. The ranker that fills its depth exactly
     * is not a depth cut either, which is the same rule one bound over.
     */
    @Test
    void reportsACappedUriSetOnEveryHit() {
        var models = new FakeModelRepository();
        models.results = List.of();
        var resources = new FakeResourceQueryRepository();
        var lookup = new FakeModelRowLookup();
        resources.annotations = annotationsNaming(800);
        lookup.rows = rows(0, 800);
        var service = service(models, resources, lookup);

        var fused = service.query(query("gly", List.of(), 40));

        assertEquals(40, fused.size(), "the fused list is cut to top, not to the URI cap");
        assertTrue(fused.stream().allMatch(hit -> hit.limits().documentUrisTruncated()),
                "every hit names the capped URI set: " + fused);
        assertFalse(fused.stream().anyMatch(hit -> hit.limits().rowLookupTruncated()),
                "the lookup itself answered inside its own bound");
        assertFalse(fused.stream().anyMatch(hit -> hit.limits().rankerDepthTruncated()),
                "200 hits fill the depth and leave nothing out");
        assertEquals(URI.create("urn:masymos:file-499"), lookup.lastUris.getLast(),
                "the first 500 distinct URIs are asked for and the rest dropped, in rank order");
    }

    /**
     * {@code C03-2} / {@code REL-007}: a cut row lookup is reported. The fake lookup answers inside
     * its own bound here, so what is asserted is the propagation the real lookup relies on: the
     * lookup's {@code truncated} reaches every hit, and the two markers of {@link FusionLimits} are
     * not confused with each other.
     */
    @Test
    void reportsACutRowLookupOnEveryHit() {
        var models = new FakeModelRepository();
        models.results = List.of(modelHit("u1", MODEL_FILE, MODEL_URI, 0.9));
        var lookup = new FakeModelRowLookup();
        lookup.truncated = true;
        lookup.rows = List.of(new ModelRow("u1", MODEL_FILE, "m1", "one", MODEL_URI, "v1", true, 100L, false));
        var resources = new FakeResourceQueryRepository();
        resources.annotations = List.of(annotation(1.0, MODEL_URI));
        var service = service(models, resources, lookup);

        var fused = service.query(query("gly", List.of(), 10));

        assertEquals(1, fused.size(), "one file is fused: " + fused);
        assertTrue(fused.getFirst().limits().rowLookupTruncated(),
                "the lookup's cut is carried to the hit");
        assertFalse(fused.getFirst().limits().documentUrisTruncated(),
                "one URI never reaches the per-ranker cap");
        assertTrue(fused.getFirst().limits().anyTruncated(), "a cut answer is flagged as cut");
    }

    /**
     * {@code C03-5}: the third bound is <em>reported</em>. 201 model hits answer a depth of 200, so the
     * probe row proves the ranker had one more to give; {@code top = 300} cannot be filled, so the
     * answer is short and every hit names the depth. Without the marker this answer is identical to one
     * from a store that holds exactly 200 files - the whole point of the flag.
     */
    @Test
    void reportsTheDepthCutOnAShortAnswer() {
        var models = new FakeModelRepository();
        models.results = modelHits(201);
        var service = service(models, new FakeResourceQueryRepository(), new FakeModelRowLookup());

        var fused = service.query(query("gly", List.of(), 300));

        assertEquals(200, fused.size(), "the depth is the ceiling and the probe row is discarded: " + fused.size());
        assertTrue(
                fused.stream().allMatch(hit -> hit.limits().rankerDepthTruncated()),
                "every hit names the depth the answer ran into");
        assertFalse(
                fused.stream().anyMatch(hit -> hit.limits().rowLookupTruncated() || hit.limits().documentUrisTruncated()),
                "no other bound was reached");
    }

    /**
     * {@code C03-5}, the other direction, and the reason the flag is not the probe alone: at
     * {@code top = 150} the same depth is reached - 201 hits for a probe of 201 - yet all 150 asked
     * files are answered, so nothing was left out and nothing may be reported. This is the false
     * positive {@code C03-2}'s review removed from the URI budget, in the depth's place.
     */
    @Test
    void aFullPageIsNotADepthCut() {
        var models = new FakeModelRepository();
        models.results = modelHits(201);
        var service = service(models, new FakeResourceQueryRepository(), new FakeModelRowLookup());

        var fused = service.query(query("gly", List.of(), 150));

        assertEquals(150, fused.size(), "a top below the depth is answered in full");
        assertFalse(
                fused.stream().anyMatch(hit -> hit.limits().anyTruncated()),
                "the depth was reached but nothing was left out, so nothing is reported: " + fused);
    }

    /**
     * {@code C03-5}: the probe is asked of the resource rankers too, because their depth shortens an
     * answer exactly as the model ranker's does. 201 annotations at {@code top = 40} - a depth of 200 -
     * are cut to the depth, the one URI they name resolves to one file, and the short answer says so;
     * exactly 200 annotations fill the depth and are not a cut.
     */
    @Test
    void reportsAResourceRankerReachedAtTheDepth() {
        var models = new FakeModelRepository();
        models.results = List.of();
        var lookup = new FakeModelRowLookup();
        lookup.rows = List.of(
                new ModelRow("u1", MODEL_FILE, "m1", "one", ANNOTATION_MODEL_URI, "v1", false, null, false));
        var resources = new FakeResourceQueryRepository();
        resources.annotations = annotations(201, ANNOTATION_MODEL_URI);
        var service = service(models, resources, lookup);

        var fused = service.query(query("gly", List.of(), 40));

        assertFalse(fused.isEmpty(), "the annotation resolves to a file: " + fused);
        assertTrue(
                fused.getFirst().limits().rankerDepthTruncated(),
                "201 annotations against a depth of 200 are a cut the answer names: " + fused);

        resources.annotations = annotations(200, ANNOTATION_MODEL_URI);

        var complete = service.query(query("gly", List.of(), 40));

        assertFalse(
                complete.getFirst().limits().rankerDepthTruncated(),
                "200 annotations fill the depth and leave nothing out: " + complete);
    }

    /**
     * The collapsed file order is the order of each file's <em>first</em> fused row, not of the row
     * that ends up representing the file. Here {@code u2} opens the MODEL_FILE block, so that file
     * takes the first place, while {@code u1} — the file's other version, at a worse best rank —
     * wins the merge on the uid tie-break and represents it. A re-sort of the collapsed list by the
     * representative would answer {@code [BIOMD0002.xml, BIOMD0001.xml]} instead.
     */
    @Test
    void keepsFileOrderAfterCollapsingAVersionWhoseBestRowArrivesSecond() {
        var models = new FakeModelRepository();
        // the file matched only through its annotation, so only the other file is a model hit
        models.results = List.of(modelHit("u3", OTHER_FILE, OTHER_URI, 0.9, "v1"));
        var lookup = new FakeModelRowLookup();
        lookup.rows = List.of(
                new ModelRow("u2", MODEL_FILE, "m1", "one", MODEL_URI, "v1", true, 100L, false),
                new ModelRow("u1", MODEL_FILE, "m1", "one", MODEL_URI, "v2", true, 100L, false));
        var resources = new FakeResourceQueryRepository();
        resources.annotations = List.of(annotation(1.0, MODEL_URI));
        var service = service(models, resources, lookup, List.of(new BestRankStrategy()));

        List<FusedModelHit> hits =
                service.query(new AggregatedQuery("gly", List.of(), 10, null, BestRankStrategy.ID, null));

        assertEquals(
                List.of(MODEL_FILE, OTHER_FILE),
                hits.stream().map(hit -> hit.row().fileId()).toList(),
                "the file keeps the place of its first fused row");
        assertEquals("u1", hits.getFirst().row().uid(), "the later version represents the file on the uid tie");
        assertEquals("v2", hits.getFirst().row().versionId(), "and it is hydrated from its lookup row");
    }

    /**
     * {@code BORDA-002}: a fused row nothing hydrated is skipped deliberately and the skip does not
     * consume the {@code top} budget — with {@code top=1} a budget-consuming skip would answer
     * nothing. Unreachable through the HTTP path today, because every ranker entry comes from a model
     * hit or a lookup row and both are collected by uid, so this drives it the way the deferred row
     * says it could become reachable: a strategy that synthesises a {@code uid}.
     */
    @Test
    void skipsAFusedRowWithNoCollectedModelRow() {
        var models = new FakeModelRepository();
        models.results = List.of(modelHit("u1", MODEL_FILE, MODEL_URI, 0.9));
        var service = service(
                models,
                new FakeResourceQueryRepository(),
                new FakeModelRowLookup(),
                List.of(new GhostRowStrategy()));

        List<FusedModelHit> hits =
                service.query(new AggregatedQuery("gly", List.of(), 1, null, GhostRowStrategy.ID, null));

        assertEquals(1, hits.size(), "the synthesised row is skipped, not returned without a model row");
        assertEquals("u1", hits.getFirst().row().uid(), "and the real row still fits the one-row budget");
    }

    // --- helpers -------------------------------------------------------------------------------

    private static RankFusionQueryService service(
            ModelRepository models,
            ResourceQueryRepository resources,
            ModelRowLookup rows,
            List<RankFusionStrategy> strategies) {
        return new RankFusionQueryService(models, resources, rows, strategies);
    }

    /** A service with both shipped strategies, as the service module wires it. */
    private static RankFusionQueryService service(
            ModelRepository models, ResourceQueryRepository resources, ModelRowLookup rows) {
        return service(models, resources, rows, List.of(new BordaCombMnzStrategy(), new BestRankStrategy()));
    }

    private static AggregatedQuery query(String keyword, List<QueryClause> clauses, int top) {
        return new AggregatedQuery(keyword, clauses, top, null, BordaCombMnzStrategy.ID, FusionWeights.none());
    }

    private static QueryClause clause(QueryField field, String keyword) {
        return new QueryClause(field, keyword);
    }

    private static ModelSearchResult modelHit(String uid, String filename, URI documentUri, double score) {
        return modelHit(uid, filename, documentUri, score, "v1");
    }

    /** A hit as the model query reports it since QRY-2: with the version label and document facts. */
    private static ModelSearchResult modelHit(
            String uid, String filename, URI documentUri, double score, String versionId) {
        return new ModelSearchResult(
                uid,
                "model-" + uid,
                "name-" + uid,
                documentUri,
                filename,
                score,
                List.of(),
                versionId,
                List.of(),
                "modelFulltextV6",
                true,
                100L,
                false);
    }

    private static AnnotationSearchResult annotation(double score, URI relatedModelUri) {
        return new AnnotationSearchResult("https://identifiers.org/go/GO:0008150", List.of(relatedModelUri), score);
    }

    /** {@code count} model hits, one file each, in the order the model ranker would return them. */
    private static List<ModelSearchResult> modelHits(int count) {
        var hits = new ArrayList<ModelSearchResult>(count);
        for (var index = 0; index < count; index++) {
            hits.add(modelHit(
                    "u" + index,
                    "file-" + index + ".xml",
                    URI.create("urn:masymos:probe-" + index),
                    1.0 - index * 0.001));
        }
        return List.copyOf(hits);
    }

    /** {@code count} annotation hits naming the same document URI, highest score first. */
    private static List<AnnotationSearchResult> annotations(int count, URI relatedModelUri) {
        var hits = new ArrayList<AnnotationSearchResult>(count);
        for (var index = 0; index < count; index++) {
            hits.add(annotation(count - index, relatedModelUri));
        }
        return List.copyOf(hits);
    }

    /** One annotation hit naming several model files, as a file-wide resource hit can. */
    private static AnnotationSearchResult annotationNaming(double score, List<URI> relatedModelUris) {
        return new AnnotationSearchResult("https://identifiers.org/go/GO:0008150", relatedModelUris, score);
    }

    /**
     * {@code distinctUris} distinct document URIs spread over {@code distinctUris / 4} annotation hits,
     * four files per hit, in rank order - the shape a file-wide resource hit has, and the reason a
     * ranker inside its depth can still exceed the 500-URI budget.
     */
    private static List<AnnotationSearchResult> annotationsNaming(int distinctUris) {
        var hits = new ArrayList<AnnotationSearchResult>(distinctUris / 4 + 1);
        var score = (double) distinctUris;
        for (var index = 0; index + 3 < distinctUris; index += 4) {
            hits.add(annotationNaming(
                    score,
                    List.of(
                            URI.create("urn:masymos:file-" + index),
                            URI.create("urn:masymos:file-" + (index + 1)),
                            URI.create("urn:masymos:file-" + (index + 2)),
                            URI.create("urn:masymos:file-" + (index + 3)))));
            score -= 1;
        }
        return List.copyOf(hits);
    }

    /** One lookup row per URI {@code from} to {@code from + count - 1}, one file each. */
    private static List<ModelRow> rows(int from, int count) {
        var modelRows = new ArrayList<ModelRow>(count);
        for (var index = from; index < from + count; index++) {
            modelRows.add(new ModelRow(
                    "uid-" + index,
                    "file-" + index + ".xml",
                    "m" + index,
                    "M",
                    URI.create("urn:masymos:file-" + index),
                    "v1",
                    false,
                    null,
                    false));
        }
        return List.copyOf(modelRows);
    }

    private static PersonSearchResult person(double score, URI relatedModelUri) {
        return new PersonSearchResult(
                new PersonSummary("Jane", "Doe", "jane@example.org", "Example Institute"),
                List.of(relatedModelUri),
                score);
    }

    private static PublicationSearchResult publication(double score, URI relatedModelUri) {
        return new PublicationSearchResult(
                "Publication title",
                "Journal",
                "Institute",
                "2026",
                List.of(new PersonSummary("Jane", "Doe", "", "")),
                List.of(relatedModelUri),
                score);
    }

    /**
     * The trigger {@code BORDA-002} names: a strategy that emits a row no ranker produced, here ahead
     * of the real ones so the skip branch is the first thing the cut meets.
     */
    private static final class GhostRowStrategy implements RankFusionStrategy {
        private static final String ID = "ghostRow";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String description() {
            return "Test strategy: prepends a synthesised row no ranker returned.";
        }

        @Override
        public boolean producesScore() {
            return false;
        }

        @Override
        public List<RankedFusion> fuse(List<RankedList> rankers, FusionWeights weights) {
            List<RankedFusion> fused = new ArrayList<>();
            fused.add(new RankedFusion("ghost", "ghost.xml", URI.create("urn:masymos:ghost"), null, List.of()));
            for (RankedList ranker : rankers) {
                List<RankedModel> rankerHits = ranker.hits();
                for (int index = 0; index < rankerHits.size(); index++) {
                    RankedModel hit = rankerHits.get(index);
                    fused.add(new RankedFusion(
                            hit.uid(),
                            hit.fileId(),
                            hit.documentUri(),
                            null,
                            List.of(new FusionSource(ranker.ranker(), index + 1, hit.score()))));
                }
            }
            return fused;
        }
    }

    private static final class FakeModelRepository implements ModelRepository {
        private final List<ModelQueryRequest> requests = new ArrayList<>();
        private List<ModelSearchResult> results = List.of(new ModelSearchResult(
                "uid-1", "model-1", "Novak model", MODEL_URI, MODEL_FILE, 1.0));

        @Override
        public ModelIdentifier insert(ModelReference modelReference) {
            throw new UnsupportedOperationException("insert");
        }

        @Override
        public Optional<ModelIdentifier> findByUid(String uid) {
            throw new UnsupportedOperationException("findByUid");
        }

        @Override
        public Optional<ModelVersion> findLatestVersion(String fileId) {
            throw new UnsupportedOperationException("findLatestVersion");
        }

        @Override
        public List<ModelVersion> findVersions(String fileId) {
            throw new UnsupportedOperationException("findVersions");
        }

        @Override
        public Optional<ModelVersion> findVersion(String fileId, String versionId) {
            throw new UnsupportedOperationException("findVersion");
        }

        @Override
        public Optional<StoredDocument> findDocument(String uid) {
            throw new UnsupportedOperationException("findDocument");
        }

        @Override
        public Optional<ModelDeletionResult> deleteByUid(String uid, String expectedFileId, String expectedVersionId) {
            throw new UnsupportedOperationException("deleteByUid");
        }

        @Override
        public Optional<ModelDeletionResult> deleteByFileId(String fileId) {
            throw new UnsupportedOperationException("deleteByFileId");
        }

        @Override
        public List<ModelSearchResult> queryModels(ModelQueryRequest request) {
            requests.add(request);
            // A real repository applies `LIMIT $top`: answering more than the request asked for would
            // let a test see past a bound the service is supposed to enforce.
            return results.subList(0, Math.min(results.size(), request.top()));
        }
    }

    private static final class FakeResourceQueryRepository implements ResourceQueryRepository {
        private boolean called;
        private final List<ModelQueryRequest> annotationRequests = new ArrayList<>();
        private final List<ModelQueryRequest> personRequests = new ArrayList<>();
        private final List<ModelQueryRequest> publicationRequests = new ArrayList<>();
        private List<AnnotationSearchResult> annotations = List.of();
        private List<PersonSearchResult> persons = List.of();
        private List<PublicationSearchResult> publications = List.of();

        @Override
        public List<AnnotationSearchResult> queryAnnotations(ModelQueryRequest request) {
            called = true;
            annotationRequests.add(request);
            return annotations.subList(0, Math.min(annotations.size(), request.top()));
        }

        @Override
        public List<PersonSearchResult> queryPersons(ModelQueryRequest request) {
            called = true;
            personRequests.add(request);
            return persons.subList(0, Math.min(persons.size(), request.top()));
        }

        @Override
        public List<PublicationSearchResult> queryPublications(ModelQueryRequest request) {
            called = true;
            publicationRequests.add(request);
            return publications.subList(0, Math.min(publications.size(), request.top()));
        }

        private List<ModelQueryRequest> allRequests() {
            List<ModelQueryRequest> all = new ArrayList<>(annotationRequests);
            all.addAll(personRequests);
            all.addAll(publicationRequests);
            return all;
        }
    }

    private static final class FakeModelRowLookup implements ModelRowLookup {
        private List<ModelRow> rows = List.of();
        private boolean truncated;
        private int calls;
        private List<URI> lastUris = List.of();

        @Override
        public ModelRows byDocumentUris(Collection<URI> documentUris) {
            calls++;
            lastUris = List.copyOf(documentUris);
            return new ModelRows(
                    rows.stream()
                            .filter(row -> documentUris.contains(row.documentUri()))
                            .toList(),
                    truncated);
        }
    }
}

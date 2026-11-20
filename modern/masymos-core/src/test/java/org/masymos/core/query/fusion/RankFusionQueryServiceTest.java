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
    void capsThePerRankerDepthAtTwoHundred() {
        var models = new FakeModelRepository();
        var resources = new FakeResourceQueryRepository();
        var service = service(models, resources, new FakeModelRowLookup());

        service.query(query("gly", List.of(), 1000));

        assertEquals(200, models.requests.getFirst().top());
        assertEquals(3, resources.allRequests().size());
        for (ModelQueryRequest request : resources.allRequests()) {
            assertEquals(200, request.top());
        }

        service.query(query("gly", List.of(), 1));

        assertEquals(25, models.requests.get(1).top());
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
                "modelFulltextV5",
                true,
                100L,
                false);
    }

    private static AnnotationSearchResult annotation(double score, URI relatedModelUri) {
        return new AnnotationSearchResult("https://identifiers.org/go/GO:0008150", List.of(relatedModelUri), score);
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
            return results;
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
            return annotations;
        }

        @Override
        public List<PersonSearchResult> queryPersons(ModelQueryRequest request) {
            called = true;
            personRequests.add(request);
            return persons;
        }

        @Override
        public List<PublicationSearchResult> queryPublications(ModelQueryRequest request) {
            called = true;
            publicationRequests.add(request);
            return publications;
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
        private int calls;

        @Override
        public List<ModelRow> byDocumentUris(Collection<URI> documentUris) {
            calls++;
            return rows.stream()
                    .filter(row -> documentUris.contains(row.documentUri()))
                    .toList();
        }
    }
}

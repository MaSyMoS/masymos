package org.masymos.core.query.fusion;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.PersonSearchResult;
import org.masymos.core.query.PublicationSearchResult;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryEntity;
import org.masymos.core.query.QueryField;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ModelRowLookup;
import org.masymos.core.spi.ResourceQueryRepository;

/**
 * The aggregated query: runs the model, annotation, person and publication queries once, maps every
 * resource hit to the model versions it belongs to, fuses the four rankers with the requested
 * strategy, collapses the fused rows to one row per model file and cuts to {@code top}.
 *
 * <p>The per-ranker depth is widened from the caller's {@code top} ({@code 5 * top}, at least 25,
 * at most 200) because the file collapse shortens the list and a resource hit is only ever as good
 * as the best-fused version behind it. The model ranker receives the caller's keyword and every
 * clause; the three resource rankers receive the keyword and only the {@link QueryField#KEYWORD}
 * clauses, because a resource query maps a field it does not know to a bare full-text term. A
 * resource request left with no term at all is not executed and its ranker stays empty.
 *
 * <p>No Spring, no driver: the class talks only to core SPIs, so the container lane can run the
 * real composition against a database.
 */
public final class RankFusionQueryService {

    /** The smallest per-ranker depth asked for, whatever {@code top} is. */
    private static final int MIN_PER_RANKER_TOP = 25;

    /** The largest per-ranker depth asked for; {@code top} never widens a ranker beyond this. */
    private static final int MAX_PER_RANKER_TOP = 200;

    /** The widening factor: one fused file needs several ranked versions behind it. */
    private static final int PER_RANKER_FACTOR = 5;

    /**
     * The best row of one fused file: fused score descending with nulls last, then the number of
     * sources descending, then {@code uid} ascending.
     */
    private static final Comparator<RankedFusion> BEST_ROW_OF_FILE = Comparator.comparing(
                    RankedFusion::fusedScore, Comparator.nullsLast(Comparator.<Double>reverseOrder()))
            .thenComparing(ranked -> ranked.sources().size(), Comparator.reverseOrder())
            .thenComparing(RankedFusion::uid);

    /**
     * The model ranker's order: score descending, then the document URI and {@code uid} ascending.
     * The database orders by score alone, so versions with the same score arrive in an unspecified
     * order; sorting here keeps every rank derived from the model ranker — including the order a
     * file-wide hit expands into — independent of the database's internal order (QRY-11).
     */
    private static final Comparator<ModelSearchResult> MODEL_RANKER_ORDER =
            Comparator.comparingDouble(ModelSearchResult::score)
                    .reversed()
                    .thenComparing(ModelSearchResult::documentUri, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ModelSearchResult::uid, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ModelRepository models;
    private final ResourceQueryRepository resources;
    private final ModelRowLookup rows;
    private final List<RankFusionStrategy> strategies;

    /**
     * @param models the model query
     * @param resources the annotation, person and publication queries
     * @param rows the document-URI to model-row lookup
     * @param strategies the registered strategies; {@link #strategies()} and {@link #query} sort them
     *     by {@link RankFusionStrategy#id()}, so their incoming order never matters
     */
    public RankFusionQueryService(
            ModelRepository models,
            ResourceQueryRepository resources,
            ModelRowLookup rows,
            List<RankFusionStrategy> strategies) {
        this.models = Objects.requireNonNull(models, "models");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.strategies = strategies == null
                ? List.of()
                : List.copyOf(strategies.stream()
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(RankFusionStrategy::id))
                        .toList());
    }

    /** The registered strategies, sorted by id so the order never depends on bean discovery. */
    public List<RankFusionStrategy> strategies() {
        return strategies;
    }

    /**
     * Runs the aggregated query.
     *
     * @param query the validated request
     * @return one fused row per model file, ordered and cut to {@code top}; empty when every ranker
     *     came back empty
     * @throws IllegalArgumentException if the strategy id is null or names no registered strategy;
     *     the message names the known ids, so the HTTP surface can turn it into a {@code 400}
     */
    public List<FusedModelHit> query(AggregatedQuery query) {
        Objects.requireNonNull(query, "query");
        RankFusionStrategy strategy = resolveStrategy(query.strategy());
        int perRankerTop = perRankerTop(query.top());

        ModelQueryRequest modelRequest = new ModelQueryRequest(
                query.keyword(), query.clauses(), perRankerTop, query.minScore(), false, query.modelTypes());
        List<ModelSearchResult> modelHits =
                query.types().contains(QueryEntity.MODEL) ? models.queryModels(modelRequest) : List.of();

        Map<String, ModelRow> collectedRows = new HashMap<>();
        List<RankedModel> modelRanker = new ArrayList<>();
        for (ModelSearchResult hit : modelHits.stream().sorted(MODEL_RANKER_ORDER).toList()) {
            String fileId = hit.filename() == null ? hit.uid() : hit.filename();
            modelRanker.add(new RankedModel(hit.uid(), fileId, hit.documentUri(), hit.score()));
            collectedRows.putIfAbsent(
                    hit.uid(),
                    new ModelRow(
                            hit.uid(),
                            fileId,
                            hit.modelId(),
                            hit.modelName(),
                            hit.documentUri(),
                            hit.versionId(),
                            hit.documentStored(),
                            hit.documentBytes(),
                            hit.documentTruncated()));
        }

        // The model ranker's positions, by uid: the order a file-wide resource hit expands into.
        Map<String, Integer> modelRankByUid = new HashMap<>();
        for (int index = 0; index < modelRanker.size(); index++) {
            modelRankByUid.putIfAbsent(modelRanker.get(index).uid(), index + 1);
        }

        var types = query.types();
        List<RankedModel> annotationRanker = List.of();
        List<RankedModel> personRanker = List.of();
        List<RankedModel> publicationRanker = List.of();
        ModelQueryRequest resourceRequest = resourceRequest(query, perRankerTop);
        if (resourceRequest != null) {
            List<AnnotationSearchResult> annotations =
                    types.contains(QueryEntity.ANNOTATION) ? resources.queryAnnotations(resourceRequest) : List.of();
            List<PersonSearchResult> persons =
                    types.contains(QueryEntity.PERSON) ? resources.queryPersons(resourceRequest) : List.of();
            List<PublicationSearchResult> publications = types.contains(QueryEntity.PUBLICATION)
                    ? resources.queryPublications(resourceRequest)
                    : List.of();

            Set<URI> documentUris = new LinkedHashSet<>();
            for (AnnotationSearchResult hit : annotations) {
                addDocumentUris(documentUris, hit.relatedModelUris());
            }
            for (PersonSearchResult hit : persons) {
                addDocumentUris(documentUris, hit.relatedModelUris());
            }
            for (PublicationSearchResult hit : publications) {
                addDocumentUris(documentUris, hit.relatedModelUris());
            }

            Map<URI, List<ModelRow>> rowsByDocumentUri = new HashMap<>();
            if (!documentUris.isEmpty()) {
                for (ModelRow row : rows.byDocumentUris(documentUris)) {
                    if (row.uid() == null || row.documentUri() == null) {
                        continue;
                    }
                    collectedRows.putIfAbsent(row.uid(), row);
                    rowsByDocumentUri
                            .computeIfAbsent(row.documentUri(), uri -> new ArrayList<>())
                            .add(row);
                }
            }

            annotationRanker = expand(
                    annotations.stream().map(AnnotationSearchResult::relatedModelUris).toList(),
                    annotations.stream().map(AnnotationSearchResult::score).toList(),
                    rowsByDocumentUri,
                    modelRankByUid);
            personRanker = expand(
                    persons.stream().map(PersonSearchResult::relatedModelUris).toList(),
                    persons.stream().map(PersonSearchResult::score).toList(),
                    rowsByDocumentUri,
                    modelRankByUid);
            publicationRanker = expand(
                    publications.stream().map(PublicationSearchResult::relatedModelUris).toList(),
                    publications.stream().map(PublicationSearchResult::score).toList(),
                    rowsByDocumentUri,
                    modelRankByUid);
        }

        // Only the selected rankers are fused, so k counts the selection: a request narrowed to the
        // model ranker scores as if it were the only one.
        List<RankedList> rankers = new ArrayList<>(4);
        if (types.contains(QueryEntity.MODEL)) {
            rankers.add(new RankedList(Ranker.MODEL, modelRanker));
        }
        if (types.contains(QueryEntity.ANNOTATION)) {
            rankers.add(new RankedList(Ranker.ANNOTATION, annotationRanker));
        }
        if (types.contains(QueryEntity.PERSON)) {
            rankers.add(new RankedList(Ranker.PERSON, personRanker));
        }
        if (types.contains(QueryEntity.PUBLICATION)) {
            rankers.add(new RankedList(Ranker.PUBLICATION, publicationRanker));
        }
        List<RankedFusion> fused = RankFusion.fuse(rankers, strategy, query.weights());

        // The fused rows arrive in the strategy's total order, and a file's best row is the first of
        // its rows in that order, so the map's insertion order *is* the file order. Re-sorting here
        // would throw that order away for an order-only strategy, whose scores are all null.
        Map<String, RankedFusion> files = new LinkedHashMap<>();
        for (RankedFusion row : fused) {
            files.merge(row.fileId(), row, (current, candidate) -> BEST_ROW_OF_FILE.compare(candidate, current) < 0
                    ? candidate
                    : current);
        }

        List<RankedFusion> ordered = new ArrayList<>(files.values());

        List<FusedModelHit> hits = new ArrayList<>(Math.min(ordered.size(), query.top()));
        for (RankedFusion row : ordered) {
            if (hits.size() >= query.top()) {
                break;
            }
            ModelRow modelRow = collectedRows.get(row.uid());
            if (modelRow == null) {
                continue;
            }
            hits.add(new FusedModelHit(modelRow, row.fusedScore(), row.sources()));
        }
        return List.copyOf(hits);
    }

    /** The per-ranker fetch depth: {@code 5 * top}, at least 25 and at most 200. */
    private static int perRankerTop(int top) {
        long widened = (long) PER_RANKER_FACTOR * top;
        return (int) Math.min(Math.max(MIN_PER_RANKER_TOP, widened), MAX_PER_RANKER_TOP);
    }

    /**
     * The request the three resource rankers receive, or {@code null} when it would carry no term.
     * Only {@link QueryField#KEYWORD} clauses reach a resource query; a keyword that is null or
     * blank does not count as a term.
     */
    private static ModelQueryRequest resourceRequest(AggregatedQuery query, int perRankerTop) {
        List<QueryClause> keywordClauses = query.clauses().stream()
                .filter(clause -> clause.field() == QueryField.KEYWORD)
                .toList();
        boolean hasKeyword = query.keyword() != null && !query.keyword().isBlank();
        if (!hasKeyword && keywordClauses.isEmpty()) {
            return null;
        }
        return new ModelQueryRequest(query.keyword(), keywordClauses, perRankerTop, query.minScore());
    }

    private static void addDocumentUris(Set<URI> documentUris, Collection<URI> relatedModelUris) {
        for (URI uri : relatedModelUris) {
            if (uri != null) {
                documentUris.add(uri);
            }
        }
    }

    /**
     * Expands each resource hit into one entry per resolved model row, in the hit's order and with
     * the hit's native score. The rows of one hit are ordered by the model ranker — the file's
     * best-scoring version first — so a hit that resolves to several model versions occupies
     * <em>consecutive</em> ranks within its ranker rather than sharing one rank: a ranker position is
     * the only rank the fusion formula has. A hit whose URIs resolve to nothing contributes nothing.
     *
     * <p>That order is not cosmetic. A file-wide hit says nothing about <em>which</em> version is
     * meant (D9), so it must not hand the better rank — and with it the better fused score — to an
     * arbitrary version. Ordering by {@code uid} did exactly that: the representative version of a
     * file followed a random UUID, so the same corpus answered differently in two databases and the
     * container test flipped between runs (QRY-11).
     */
    private static List<RankedModel> expand(
            List<List<URI>> relatedModelUrisPerHit,
            List<Double> scores,
            Map<URI, List<ModelRow>> rowsByDocumentUri,
            Map<String, Integer> modelRankByUid) {
        List<RankedModel> ranker = new ArrayList<>();
        for (int index = 0; index < relatedModelUrisPerHit.size(); index++) {
            double score = scores.get(index);
            for (URI uri : new LinkedHashSet<>(relatedModelUrisPerHit.get(index))) {
                if (uri == null) {
                    continue;
                }
                List<ModelRow> resolved = new ArrayList<>(rowsByDocumentUri.getOrDefault(uri, List.of()));
                resolved.sort(expansionOrder(modelRankByUid));
                for (ModelRow row : resolved) {
                    ranker.add(new RankedModel(row.uid(), row.fileId(), row.documentUri(), score));
                }
            }
        }
        return ranker;
    }

    /**
     * The order the resolved versions of one hit take inside their ranker: the model ranker's
     * position first, so the file's best-scoring version leads; versions the model ranker does not
     * know (a resource-only file, or a request narrowed away from the model ranker) come last. The
     * document URI and then {@code uid} only break ties, so the order never depends on insertion
     * order.
     */
    private static Comparator<ModelRow> expansionOrder(Map<String, Integer> modelRankByUid) {
        return Comparator.comparing((ModelRow row) -> modelRankByUid.getOrDefault(row.uid(), Integer.MAX_VALUE))
                .thenComparing(ModelRow::documentUri, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ModelRow::uid, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private RankFusionStrategy resolveStrategy(String id) {
        for (RankFusionStrategy strategy : strategies) {
            if (strategy.id().equals(id)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("unknown fusion strategy '" + id + "'; known: " + knownIds());
    }

    private String knownIds() {
        return strategies.stream().map(RankFusionStrategy::id).collect(Collectors.joining(", "));
    }
}

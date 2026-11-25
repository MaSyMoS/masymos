package org.masymos.core.query.fusion;

import java.net.URI;
import java.util.ArrayList;
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
 * as the best-fused version behind it. Every ranker is fetched one row beyond that depth, so a short
 * answer can say whether the depth was the ceiling it ran into ({@code C03-5}, decision 51). The
 * model ranker receives the caller's keyword and every clause; the three resource rankers receive
 * the keyword and only the {@link QueryField#KEYWORD} clauses, because a resource query maps a field
 * it does not know to a bare full-text term. A resource request left with no term at all is not
 * executed and its ranker stays empty.
 *
 * <p>No Spring, no driver: the class talks only to core SPIs, so the container lane can run the
 * real composition against a database.
 */
public final class RankFusionQueryService {

    /** The smallest per-ranker depth asked for, whatever {@code top} is. */
    private static final int MIN_PER_RANKER_TOP = 25;

    /**
     * The largest per-ranker depth asked for; {@code top} never widens a ranker beyond this.
     *
     * <p><strong>What was measured</strong> (2026-11-24, against a container-backed store): with 300
     * files matching one keyword, {@code top = 150} answers 150 files and {@code top = 300} answers
     * <strong>200</strong> - the depth, not the request. With more than one version per file the same
     * budget buys fewer files, because the depth counts versions and only the best row of each file
     * survives the collapse: {@code perRankerTop / versionsPerFile} files at worst. That ratio is
     * arithmetic over the two measured bounds, not a third measurement, because which rows a score tie
     * returns is the database's business. {@code Neo4jContainersIT#answersAtMostTheRankerDepthHoweverLargeTopIs}
     * pins the ceiling in both directions, and its power was measured by mutation: at 100 the same
     * request answers 100.
     *
     * <p>The depth is <strong>not silent</strong> ({@code C03-5}, closed 2026-11-24, decision 51): every
     * ranker is asked for one row beyond it, and the probe row - discarded before fusion, so the depth
     * stays the ceiling - sets {@link FusionLimits#rankerDepthTruncated()} on every hit of an answer
     * that could not fill {@code top}. A page that does fill {@code top} reports nothing even though the
     * probe fired, because the depth left nothing out of <em>that</em> answer.
     */
    private static final int MAX_PER_RANKER_TOP = 200;

    /**
     * How many distinct document URIs one resource ranker may add to the row lookup ({@code REL-007}).
     * The budget is per ranker, not shared: the three resource rankers can name up to 1 500 URIs when
     * their hit sets are disjoint, and each cap is taken in rank order - highest-scoring hit first - so
     * the cut is deterministic. A cap that was reached is reported on every hit as
     * {@code limits.documentUrisTruncated} ({@code C03-2}), so a short answer says so.
     *
     * <p>One ranker reaches the cap on its own: its {@link #MAX_PER_RANKER_TOP} hits are file-wide, and
     * one annotation can name several model files, so 200 hits can exceed 500 URIs. <strong>What was
     * measured</strong> (2026-11-22, external Neo4j instance): the corpus draw carries one version per
     * source URI (71 model rows over 66 documents), and the row lookup answered 500 URIs with four
     * versions each completely - exactly its 2 000-row bound - while five versions each were cut to
     * 2 000. So 500 URIs at four versions fill the bound; the 1 500-URI union above it is stated, not
     * measured, because the wild distribution needs a store this checkout cannot produce.
     */
    static final int MAX_DOCUMENT_URIS_PER_RANKER = 500;

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
     * @return one fused row per model file, ordered and cut to {@code top}; every row carries the same
     *     {@link FusionLimits}, which says whether a {@code REL-007} bound cut the request short; empty
     *     when every ranker came back empty
     * @throws IllegalArgumentException if the strategy id is null or names no registered strategy;
     *     the message names the known ids, so the HTTP surface can turn it into a {@code 400}
     */
    public List<FusedModelHit> query(AggregatedQuery query) {
        Objects.requireNonNull(query, "query");
        RankFusionStrategy strategy = resolveStrategy(query.strategy());
        int perRankerTop = perRankerTop(query.top());
        // The probe: every ranker is asked for one row beyond its depth, the shape C03-2 gave the row
        // lookup (decision 51). A ranker that answers it held more than the depth, so a short page can
        // be attributed to the depth instead of to the size of the store. The probe row is discarded
        // below, so the depth stays the answer's ceiling.
        int probeTop = perRankerTop + 1;

        ModelQueryRequest modelRequest = new ModelQueryRequest(
                query.keyword(), query.clauses(), probeTop, query.minScore(), false, query.modelTypes());
        List<ModelSearchResult> modelHits =
                query.types().contains(QueryEntity.MODEL) ? models.queryModels(modelRequest) : List.of();
        boolean rankerDepthReached = modelHits.size() > perRankerTop;
        modelHits = withinDepth(modelHits, perRankerTop);

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
        // The two REL-007 bounds report themselves through FusionLimits (C03-2): a caller must be able
        // to tell a short answer from a complete one. Both are request-wide facts, so every hit below
        // carries the same values. The third bound, the per-ranker depth, is a probe result that only
        // becomes a report once the answer is known to be short - see below (C03-5, decision 51).
        boolean documentUrisTruncated = false;
        boolean rowLookupTruncated = false;
        ModelQueryRequest resourceRequest = resourceRequest(query, probeTop);
        if (resourceRequest != null) {
            List<AnnotationSearchResult> annotations =
                    types.contains(QueryEntity.ANNOTATION) ? resources.queryAnnotations(resourceRequest) : List.of();
            List<PersonSearchResult> persons =
                    types.contains(QueryEntity.PERSON) ? resources.queryPersons(resourceRequest) : List.of();
            List<PublicationSearchResult> publications = types.contains(QueryEntity.PUBLICATION)
                    ? resources.queryPublications(resourceRequest)
                    : List.of();

            // The same one-row probe as the model ranker's: a resource ranker's depth shortens an answer
            // exactly as the model ranker's does, so all four are measured (decision 51).
            rankerDepthReached |= annotations.size() > perRankerTop
                    || persons.size() > perRankerTop
                    || publications.size() > perRankerTop;
            annotations = withinDepth(annotations, perRankerTop);
            persons = withinDepth(persons, perRankerTop);
            publications = withinDepth(publications, perRankerTop);

            Set<URI> documentUris = new LinkedHashSet<>();
            // Three statements, not one expression: every ranker must contribute, so its own cap is
            // measured even after an earlier one was reached.
            documentUrisTruncated |=
                    collectDocumentUris(documentUris,
                            annotations.stream().map(AnnotationSearchResult::relatedModelUris).toList());
            documentUrisTruncated |=
                    collectDocumentUris(documentUris,
                            persons.stream().map(PersonSearchResult::relatedModelUris).toList());
            documentUrisTruncated |=
                    collectDocumentUris(documentUris,
                            publications.stream().map(PublicationSearchResult::relatedModelUris).toList());

            Map<URI, List<ModelRow>> rowsByDocumentUri = new HashMap<>();
            if (!documentUris.isEmpty()) {
                var resolved = rows.byDocumentUris(documentUris);
                rowLookupTruncated = resolved.truncated();
                for (ModelRow row : resolved.rows()) {
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

        // The fused rows arrive in the strategy's total order. A map keyed by file keeps the position
        // it saw first, and merge below only replaces the *value* - the representative row - so the
        // insertion order is the order of each file's first fused row, whatever row is chosen to
        // represent that file. Nothing may re-sort this map: an order-only strategy has no scores to
        // sort by, and a sort of the collapsed list would re-order a file by a row that did not
        // decide its place.
        //
        // That the representative is chosen independently is sound because BEST_ROW_OF_FILE invents
        // no key the fused order does not have: its keys are fused score, then source count, then
        // uid - the first, second and last keys of RankFusion.DETERMINISTIC_ORDER - and the one key
        // it omits, documentUri, is constant across one file's rows, because the collapse key is the
        // file and the document URI is a property of the file. For a score-producing strategy, whose
        // order *is* DETERMINISTIC_ORDER, the representative is therefore the file's first row. For
        // an order-only strategy it can be a later one: bestRank orders by best rank first, a key
        // BEST_ROW_OF_FILE does not hold, so a version with a worse rank can carry a better score
        // key and win the merge while the file keeps the place of the row that arrived first
        // (RankFusionQueryServiceTest#keepsFileOrderAfterCollapsingAVersionWhoseBestRowArrivesSecond).
        Map<String, RankedFusion> files = new LinkedHashMap<>();
        for (RankedFusion row : fused) {
            files.merge(row.fileId(), row, (current, candidate) -> BEST_ROW_OF_FILE.compare(candidate, current) < 0
                    ? candidate
                    : current);
        }

        List<RankedFusion> ordered = new ArrayList<>(files.values());

        // The depth cut is reported only when it can explain something: an answer shorter than `top`.
        // At `top = 150` on a store of 300 files the depth is reached as well, yet every asked row is
        // answered, and marking that answer cut would be the false positive C03-2's review removed from
        // the URI budget (decision 51). What remains stated is the difference: `true` says "the depth
        // was reached and this page is short", never "more files existed".
        boolean rankerDepthTruncated = rankerDepthReached && ordered.size() < query.top();

        List<FusedModelHit> hits = new ArrayList<>(Math.min(ordered.size(), query.top()));
        for (RankedFusion row : ordered) {
            if (hits.size() >= query.top()) {
                break;
            }
            ModelRow modelRow = collectedRows.get(row.uid());
            // Skipped deliberately, not by omission: a hit carries the model's identity and document
            // facts, and a fused row nothing hydrated has none to carry, so returning it would mean
            // inventing them. The skip does not consume the top budget - only an added hit does.
            // Unreachable through the HTTP path today (BORDA-002): every ranker entry comes from a
            // model hit or from a row the lookup returned, and both are collected by uid above, so
            // only a strategy that synthesises a uid can produce a row this does not know
            // (RankFusionQueryServiceTest#skipsAFusedRowWithNoCollectedModelRow drives it that way).
            if (modelRow == null) {
                continue;
            }
            hits.add(new FusedModelHit(
                    modelRow,
                    row.fusedScore(),
                    row.sources(),
                    new FusionLimits(rowLookupTruncated, documentUrisTruncated, rankerDepthTruncated)));

        }
        return List.copyOf(hits);
    }

    /** The per-ranker fetch depth: {@code 5 * top}, at least 25 and at most 200. */
    private static int perRankerTop(int top) {
        long widened = (long) PER_RANKER_FACTOR * top;
        return (int) Math.min(Math.max(MIN_PER_RANKER_TOP, widened), MAX_PER_RANKER_TOP);
    }

    /**
     * The rows of one ranker inside its depth: the probe row past it is dropped, so the depth stays the
     * answer's ceiling and only its existence is read ({@code C03-5}, decision 51).
     */
    private static <T> List<T> withinDepth(List<T> hits, int perRankerTop) {
        return hits.size() > perRankerTop ? hits.subList(0, perRankerTop) : hits;
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

    /**
     * Adds the document URIs of one ranker, highest-scoring hit first, giving this ranker at most
     * {@link #MAX_DOCUMENT_URIS_PER_RANKER} distinct URIs. The budget is per ranker source, so one
     * verbose ranker cannot starve the other two; a URI an earlier ranker already contributed costs
     * this ranker nothing.
     *
     * @return whether this ranker had <em>more</em> distinct document URIs than its budget, so URIs it
     *     would have contributed were left out; reported on every hit as
     *     {@code limits.documentUrisTruncated}. A ranker that fills the budget exactly is
     *     {@code false}: reaching the cap is not a cut, and reporting one would be a false positive on
     *     a complete answer ({@code C03-2}, found by review).
     */
    private static boolean collectDocumentUris(Set<URI> documentUris, List<List<URI>> perHitUris) {
        var collected = 0;
        var capped = false;
        for (var relatedModelUris : perHitUris) {
            if (relatedModelUris == null) {
                continue;
            }
            for (var uri : relatedModelUris) {
                if (uri == null || documentUris.contains(uri)) {
                    // An earlier ranker already asked for this URI, or this one did: it costs nothing,
                    // so it must not consume the budget and must not be reported as dropped.
                    continue;
                }
                if (collected >= MAX_DOCUMENT_URIS_PER_RANKER) {
                    capped = true;
                    continue;
                }
                documentUris.add(uri);
                collected++;
            }
        }
        return capped;
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

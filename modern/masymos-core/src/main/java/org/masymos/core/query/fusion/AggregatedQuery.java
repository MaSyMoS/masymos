package org.masymos.core.query.fusion;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.masymos.core.model.ModelType;
import org.masymos.core.query.PageCursor;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryEntity;

/**
 * The aggregated query after validation: a free-text keyword, clauses that reach the model ranker
 * only, the requested number of model files, an optional score floor, the strategy id, the
 * per-ranker weights, the entities to fuse and the optional model-kind filter.
 *
 * <p>{@code types} defaults to the four fusable entities, {@code MODEL}, {@code ANNOTATION},
 * {@code PERSON} and {@code PUBLICATION}; {@code SEDML} and {@code ARCHIVE} are rejected because
 * they have their own endpoints. {@code modelTypes} defaults to empty, which means "every kind";
 * only {@code SBML} and {@code CELLML} are accepted.
 */
public record AggregatedQuery(
        String keyword,
        List<QueryClause> clauses,
        int top,
        Double minScore,
        String strategy,
        FusionWeights weights,
        Set<QueryEntity> types,
        Set<ModelType> modelTypes,
        String cursor
) {
    private static final int DEFAULT_TOP = 10;

    /** The entities this query can fuse; the default of {@code types}. */
    private static final Set<QueryEntity> FUSABLE_TYPES =
            Set.of(QueryEntity.MODEL, QueryEntity.ANNOTATION, QueryEntity.PERSON, QueryEntity.PUBLICATION);

    /** The model kinds the model ranker can filter on; an empty filter means every kind. */
    private static final Set<ModelType> FILTERABLE_MODEL_TYPES = Set.of(ModelType.SBML, ModelType.CELLML);

    public AggregatedQuery {
        clauses = clauses == null ? List.<QueryClause>of() : List.copyOf(clauses);
        top = top <= 0 ? DEFAULT_TOP : top;
        weights = weights == null ? FusionWeights.none() : weights;
        types = types == null ? FUSABLE_TYPES : Set.copyOf(types);
        modelTypes = modelTypes == null ? Set.of() : Set.copyOf(modelTypes);
        if (types.isEmpty()) {
            throw new IllegalArgumentException(
                    "types must not be empty; accepted: " + wireNames(FUSABLE_TYPES));
        }
        types.stream()
                .filter(type -> !FUSABLE_TYPES.contains(type))
                .findFirst()
                .ifPresent(notFusable -> {
                    throw new IllegalArgumentException("types cannot contain '" + notFusable.wire()
                            + "'; accepted: " + wireNames(FUSABLE_TYPES));
                });
        modelTypes.stream()
                .filter(modelType -> !FILTERABLE_MODEL_TYPES.contains(modelType))
                .findFirst()
                .ifPresent(unsupported -> {
                    throw new IllegalArgumentException("modelTypes cannot contain '" + unsupported
                            + "'; accepted: " + name(ModelType.SBML) + ", " + name(ModelType.CELLML));
                });
    }

    /** A query over the four fusable entities without a kind filter. */
    public AggregatedQuery(
            String keyword,
            List<QueryClause> clauses,
            int top,
            Double minScore,
            String strategy,
            FusionWeights weights) {
        this(keyword, clauses, top, minScore, strategy, weights, FUSABLE_TYPES, Set.of(), null);
    }

    /** The same query without a kind filter and without a cursor - the shape every caller had before {@code OPN-6}. */
    public AggregatedQuery(
            String keyword,
            List<QueryClause> clauses,
            int top,
            Double minScore,
            String strategy,
            FusionWeights weights,
            Set<QueryEntity> types) {
        this(keyword, clauses, top, minScore, strategy, weights, types, Set.of(), null);
    }

    /**
     * The same query starting after a cursor ({@code OPN-6}). The cursor travels as a request parameter
     * rather than in the JSON body, for the reason the model lane's does: it is a position in a result set,
     * not part of the query.
     */
    public AggregatedQuery withCursor(String cursor) {
        return new AggregatedQuery(
                keyword, clauses, top, minScore, strategy, weights, types, modelTypes, cursor);
    }

    /**
     * The same query with every filter and no cursor - the shape every caller had before {@code OPN-6}
     * added the ninth component, kept so a keyset stays opt-in instead of a parameter everybody passes.
     */
    public AggregatedQuery(
            String keyword,
            List<QueryClause> clauses,
            int top,
            Double minScore,
            String strategy,
            FusionWeights weights,
            Set<QueryEntity> types,
            Set<ModelType> modelTypes) {
        this(keyword, clauses, top, minScore, strategy, weights, types, modelTypes, null);
    }

    /**
     * The fingerprint a cursor must carry to belong to this request ({@code OPN-6}). The lane name is the
     * first component, as on every other lane, and every field that changes the fused result set follows:
     * the strategy and the weights decide the order, {@code types} decides which rankers run, and
     * {@code top} is a page size a sweep repeats. The weights are rendered in {@link Ranker}'s own
     * declaration order, so two requests that carry the same weights in different insertion order are the
     * same request.
     */
    public String fingerprint() {
        var clausesText = clauses.stream()
                .map(clause -> clause.field() + ":" + clause.keyword())
                .reduce("", (joined, next) -> joined + ";" + next);
        var weightsText = new StringBuilder();
        for (var ranker : Ranker.values()) {
            weightsText.append(ranker).append('=').append(weights.weight(ranker)).append(';');
        }
        return PageCursor.fingerprint(
                "aggregated",
                keyword,
                clausesText,
                Integer.toString(top),
                String.valueOf(minScore),
                strategy,
                weightsText.toString(),
                types.toString(),
                modelTypes.toString());
    }

    private static String wireNames(Set<QueryEntity> entities) {
        return entities.stream().map(QueryEntity::wire).sorted().collect(Collectors.joining(", "));
    }

    private static String name(ModelType modelType) {
        return modelType.name();
    }
}

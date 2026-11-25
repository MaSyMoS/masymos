package org.masymos.core.query.fusion;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.masymos.core.model.ModelType;
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
        Set<ModelType> modelTypes
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
        this(keyword, clauses, top, minScore, strategy, weights, FUSABLE_TYPES, Set.of());
    }

    private static String wireNames(Set<QueryEntity> entities) {
        return entities.stream().map(QueryEntity::wire).sorted().collect(Collectors.joining(", "));
    }

    private static String name(ModelType modelType) {
        return modelType.name();
    }
}

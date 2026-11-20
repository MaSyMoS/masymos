package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.masymos.core.model.ModelType;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryEntity;
import org.masymos.core.query.fusion.AggregatedQuery;
import org.masymos.core.query.fusion.FusionWeights;
import org.masymos.core.query.fusion.Ranker;

@Schema(
        description =
                "Aggregated query; a keyword or at least one clause, and a strategy, are required. The response has"
                        + " one row per model file.")
public record AggregatedQueryRequest(
        @Schema(description = "Free-text search term across all fields.", example = "glycolysis")
        String keyword,
        @Schema(
                description =
                        "Field-scoped search clauses. Clauses on fields other than KEYWORD reach the model ranker only.")
        List<@Valid QueryClauseRequest> clauses,
        @Positive @Schema(
                description = "Maximum number of model files to return; defaults to 10. Counts files, not versions.",
                example = "10")
        Integer top,
        @PositiveOrZero @Schema(
                description =
                        "Absolute score floor for the annotation, person and publication rankers; omitted means no cut. Ignored by the model ranker.",
                example = "0.5")
        Double minScore,
        @NotBlank @Schema(
                description = "Fusion strategy id; see GET /api/v1/queries/strategies.",
                example = "bordaCombMnz",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String strategy,
        @Schema(
                description =
                        "Per-ranker weights keyed by model, annotation, person or publication; omitted means 1.0 for every ranker. Affects bordaCombMnz only.",
                example = "{\"model\": 4, \"annotation\": 3, \"person\": 1, \"publication\": 1}")
        Map<String, Double> weights,
        @Schema(
                description =
                        "Entities to fuse; omitted means all four. Accepted: model, annotation, person, publication. sedml and archive are rejected because they have their own endpoints; k counts the selected rankers only.",
                example = "[\"model\", \"annotation\"]")
        List<String> types,
        @Schema(
                description =
                        "Restrict the model ranker to these model kinds; omitted or empty means every kind. Accepted: SBML, CELLML.",
                example = "[\"SBML\"]")
        List<String> modelTypes
) {
    /**
     * Builds the domain request.
     *
     * @throws IllegalArgumentException if a weight key is not a ranker wire name, a weight is
     *     non-positive, a type is unknown or not fusable, or a model kind is not SBML or CELLML; the
     *     API exception handler turns each of those into a {@code 400}
     */
    public AggregatedQuery toAggregatedQuery() {
        var domainClauses = clauses == null
                ? List.<QueryClause>of()
                : clauses.stream().map(QueryClauseRequest::toQueryClause).toList();
        return new AggregatedQuery(
                keyword,
                domainClauses,
                top == null ? 10 : top,
                minScore,
                strategy,
                toFusionWeights(),
                toTypes(),
                toModelTypes());
    }

    private FusionWeights toFusionWeights() {
        if (weights == null) {
            return FusionWeights.none();
        }
        Map<Ranker, Double> byRanker = new EnumMap<>(Ranker.class);
        weights.forEach((key, weight) -> byRanker.put(Ranker.fromWire(key), weight));
        return new FusionWeights(byRanker);
    }

    /**
     * A null selection keeps the core default (all four fusable entities); the core record rejects an
     * empty selection and the two entities that are not fusable.
     */
    private Set<QueryEntity> toTypes() {
        if (types == null) {
            return null;
        }
        return types.stream().map(QueryEntity::fromWire).collect(Collectors.toUnmodifiableSet());
    }

    private Set<ModelType> toModelTypes() {
        if (modelTypes == null) {
            return Set.of();
        }
        return modelTypes.stream()
                .map(QueryModelsRequest::modelType)
                .collect(Collectors.toUnmodifiableSet());
    }

    @AssertTrue(message = "keyword or at least one clause must be provided") public boolean hasSearchTerm() {
        return (keyword != null && !keyword.isBlank()) || (clauses != null && !clauses.isEmpty());
    }
}

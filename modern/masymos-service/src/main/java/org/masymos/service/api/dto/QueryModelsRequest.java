package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.masymos.core.model.ModelType;
import org.masymos.core.query.ModelQueryRequest;

@Schema(description = "Model search request; a keyword or at least one clause is required.")
public record QueryModelsRequest(
        @Schema(description = "Free-text search term across all fields.", example = "glycolysis")
        String keyword,
        @Schema(description = "Field-scoped search clauses.")
        List<@Valid QueryClauseRequest> clauses,
        @Positive @Schema(description = "Maximum number of results to return; defaults to 10.", example = "10")
        Integer top,
        @PositiveOrZero @Schema(
                description =
                        "Absolute score floor for annotation, person and publication queries; omitted means no cut. Ignored by model queries (legacy had no model cut).",
                example = "0.5")
        Double minScore,
        @Schema(
                description =
                        "Group hits by fileId so one model carries its matching versions; top then counts groups. Ignored by the annotation, person and publication endpoints.",
                example = "false")
        Boolean groupByVersion,
        @Schema(
                description =
                        "Restrict the search to these model kinds; omitted or empty means every kind. Accepted: SBML, CELLML. Ignored by the SED-ML and archive endpoints, and by the annotation, person and publication endpoints.",
                example = "[\"SBML\", \"CELLML\"]")
        List<String> modelTypes,
        @Schema(
                description =
                        "Restrict the result to models that use or define this unit; prefix forms such as cm are accepted. An unknown unit is rejected with 400.",
                example = "[\"metre\"]")
        List<String> units
) {
    /** A request without the unit filter; kept so existing callers and tests compile unchanged. */
    public QueryModelsRequest(
            String keyword,
            List<QueryClauseRequest> clauses,
            Integer top,
            Double minScore,
            Boolean groupByVersion,
            List<String> modelTypes) {
        this(keyword, clauses, top, minScore, groupByVersion, modelTypes, null);
    }

    /**
     * Builds the domain request, including the optional kind filter and the optional unit filter.
     *
     * @throws IllegalArgumentException if a modelTypes value is not SBML or CELLML; the API exception
     *     handler turns that into a {@code 400}
     */
    public ModelQueryRequest toModelQueryRequest() {
        return new ModelQueryRequest(
                keyword,
                domainClauses(),
                topOrDefault(),
                minScore,
                Boolean.TRUE.equals(groupByVersion),
                toModelTypes(),
                domainUnits());
    }

    /**
     * Builds the domain request for an endpoint the kind filter and the grouping flag do not apply
     * to: {@code groupByVersion} is off and {@code modelTypes} is empty, never evaluated.
     *
     * <p>Not parsing the kinds is the point, not an omission. The SED-ML and archive endpoints state
     * that {@code modelTypes} is ignored, so a value no query endpoint can filter on — {@code SEDML},
     * {@code OWL}, {@code P2M}, a typo — must not become a {@code 400} on an endpoint that never
     * applies it. The value is rejected only where it is used, in
     * {@link #toModelQueryRequest()}.
     */
    public ModelQueryRequest toUnfilteredModelQueryRequest() {
        return new ModelQueryRequest(keyword, domainClauses(), topOrDefault(), minScore, false, Set.of(), domainUnits());
    }

    /** The clauses in domain form; an absent list is no clause at all. */
    private List<org.masymos.core.query.QueryClause> domainClauses() {
        return clauses == null
                ? List.of()
                : clauses.stream().map(QueryClauseRequest::toQueryClause).toList();
    }

    /** The depth the query runs with; absent means 10. */
    private int topOrDefault() {
        return top == null ? 10 : top;
    }

    /** The unit filter in domain form: trimmed entries, blanks dropped, absent means no filter. */
    private List<String> domainUnits() {
        return units == null
                ? List.of()
                : units.stream()
                        .filter(unit -> unit != null && !unit.isBlank())
                        .map(String::trim)
                        .toList();
    }

    private Set<ModelType> toModelTypes() {
        if (modelTypes == null || modelTypes.isEmpty()) {
            return Set.of();
        }
        return modelTypes.stream()
                .map(QueryModelsRequest::modelType)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Resolves a kind the query surface can filter on. SED-ML, OWL and P2M are rejected as well: no
     * query endpoint filters on them.
     *
     * @throws IllegalArgumentException unless the value is SBML or CELLML
     */
    static ModelType modelType(String value) {
        for (ModelType accepted : Set.of(ModelType.SBML, ModelType.CELLML)) {
            if (accepted.name().equals(value == null ? null : value.toUpperCase(Locale.ROOT).trim())) {
                return accepted;
            }
        }
        throw new IllegalArgumentException(
                "unsupported model type '" + value + "'; accepted: SBML, CELLML");
    }

    @AssertTrue(message = "keyword or at least one clause must be provided") public boolean hasSearchTerm() {
        return (keyword != null && !keyword.isBlank()) || (clauses != null && !clauses.isEmpty());
    }
}

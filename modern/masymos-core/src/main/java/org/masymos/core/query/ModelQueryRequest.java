package org.masymos.core.query;

import java.util.List;
import java.util.Set;
import org.masymos.core.model.ModelType;

/**
 * One model query after validation: the free-text keyword, the clauses that refine it, the
 * requested number of results, an optional score floor, the version-grouping flag, the optional
 * kind filter and the optional unit filter. An empty {@code modelTypes} means "every kind", exactly
 * as before the filter existed; the only accepted values are the kinds that carry a label,
 * {@code SBML} and {@code CELLML}. An empty {@code units} means "no unit narrowing" (CML-1).
 */
public record ModelQueryRequest(
        String keyword,
        List<QueryClause> clauses,
        int top,
        Double minScore,
        boolean groupByVersion,
        Set<ModelType> modelTypes,
        List<String> units
) {
    public ModelQueryRequest {
        clauses = clauses == null ? List.of() : List.copyOf(clauses);
        top = top <= 0 ? 10 : top;
        modelTypes = modelTypes == null ? Set.of() : Set.copyOf(modelTypes);
        units = units == null ? List.of() : units.stream()
                .filter(unit -> unit != null && !unit.isBlank())
                .map(String::trim)
                .toList();
    }

    /** A request with version grouping but without a kind filter. */
    public ModelQueryRequest(
            String keyword, List<QueryClause> clauses, int top, Double minScore, boolean groupByVersion) {
        this(keyword, clauses, top, minScore, groupByVersion, Set.of(), List.of());
    }

    public ModelQueryRequest(
            String keyword,
            List<QueryClause> clauses,
            int top,
            Double minScore,
            boolean groupByVersion,
            Set<ModelType> modelTypes) {
        this(keyword, clauses, top, minScore, groupByVersion, modelTypes, List.of());
    }

    /** A request without a score floor; model queries ignore the floor, see the QRY-4 design. */
    public ModelQueryRequest(String keyword, List<QueryClause> clauses, int top, Double minScore) {
        this(keyword, clauses, top, minScore, false, Set.of(), List.of());
    }

    public ModelQueryRequest(String keyword, List<QueryClause> clauses, int top) {
        this(keyword, clauses, top, null, false, Set.of(), List.of());
    }
}

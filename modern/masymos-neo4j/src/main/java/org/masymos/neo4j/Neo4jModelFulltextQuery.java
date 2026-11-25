package org.masymos.neo4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.masymos.core.annotation.AnnotationUriResolver;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ExactText;
import org.masymos.core.query.IndexText;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;
import org.masymos.core.query.QueryPlan;

final class Neo4jModelFulltextQuery {
    /**
     * The renderer's hard backstop: a rendered Lucene expression never exceeds this length. It equals
     * {@code ModelQueryRequest.MAX_RENDERED_QUERY_CHARS} on purpose, so the truncation branch is
     * unreachable through the HTTP path - a direct constructor call, a CLI path or a future caller that
     * skips the domain record is what it exists for.
     */
    static final int MAX_RENDERED_CLAUSE_CHARS = 16384;

    private static final AnnotationUriResolver URI_RESOLVER = new AnnotationUriResolver();

    private static final Map<QueryField, FieldMapping> FIELD_MAPPINGS = Map.ofEntries(
            Map.entry(QueryField.ID, new FieldMapping("modelId", 3, "modelIdExact")),
            Map.entry(QueryField.NAME, new FieldMapping("name", 3)),
            Map.entry(QueryField.URI, new FieldMapping("sourceUri", 2, "sourceUriExact")),
            Map.entry(QueryField.COMPONENT, new FieldMapping("component", 2)),
            Map.entry(QueryField.VARIABLE, new FieldMapping("variable", 2)),
            Map.entry(QueryField.COMPARTMENT, new FieldMapping("compartment", 2)),
            Map.entry(QueryField.SPECIES, new FieldMapping("species", 2)),
            Map.entry(QueryField.REACTION, new FieldMapping("reaction", 2)),
            Map.entry(QueryField.PARAMETER, new FieldMapping("parameter", 2)),
            Map.entry(QueryField.CREATOR, new FieldMapping("creator", 1)),
            Map.entry(QueryField.AUTHOR, new FieldMapping("author", 2)),
            Map.entry(QueryField.TITLE, new FieldMapping("title", 2)),
            Map.entry(QueryField.ABSTRACT, new FieldMapping("abstractText", 1)),
            Map.entry(QueryField.AFFILIATION, new FieldMapping("affiliation", 1)),
            Map.entry(QueryField.JOURNAL, new FieldMapping("journal", 1)),
            Map.entry(QueryField.YEAR, new FieldMapping("year", 1)),
            Map.entry(QueryField.FAMILYNAME, new FieldMapping("familyName", 1)),
            Map.entry(QueryField.GIVENNAME, new FieldMapping("givenName", 1)),
            Map.entry(QueryField.EMAIL, new FieldMapping("email", 1)),
            Map.entry(QueryField.ORGANIZATION, new FieldMapping("organization", 1)),
            Map.entry(QueryField.MODEL_SOURCE, new FieldMapping("modelSource", 1)),
            Map.entry(QueryField.OUTPUT_TYPE, new FieldMapping("outputType", 1)),
            Map.entry(QueryField.DATA_LABEL, new FieldMapping("dataLabel", 1)),
            Map.entry(QueryField.SIM_KISAO, new FieldMapping("simKisao", 1)),
            Map.entry(QueryField.SIM_TYPE, new FieldMapping("simType", 1)),
            Map.entry(QueryField.MATH, new FieldMapping("math", 1)),
            Map.entry(QueryField.RESOURCE_URI, new FieldMapping("resourceUri", 4)),
            Map.entry(QueryField.RESOURCE_TEXT, new FieldMapping("resourceText", 3)),
            Map.entry(QueryField.NON_RDF, new FieldMapping("nonRdf", 2)),
            Map.entry(QueryField.SBML_LEVEL, new FieldMapping("sbmlLevel", 2)),
            Map.entry(QueryField.SBML_VERSION, new FieldMapping("sbmlVersion", 2)),
            Map.entry(QueryField.CELLML_VERSION, new FieldMapping("cellmlVersion", 2)),
            Map.entry(QueryField.SCHEMA_VERSION, new FieldMapping(
                    List.of("sbmlLevel", "sbmlVersion", "cellmlVersion", "sedmlLevel", "sedmlVersion"),
                    2,
                    null
            ))
    );

    private Neo4jModelFulltextQuery() {}

    /**
     * Splits the request into the Lucene expression and the equality predicates that the Cypher
     * builders AND together (QRY-8). A clause on a field with an exact copy becomes an
     * {@link ExactMatch} and stays out of the Lucene string; every other clause keeps the historical
     * rendering. Empty text plus no exact match means the request has nothing to query.
     */
    static QueryPlan plan(ModelQueryRequest request) {
        var text = new StringBuilder();
        var exactMatches = new ArrayList<ExactMatch>();
        if (request.keyword() != null && !request.keyword().isBlank()) {
            text.append(escape(request.keyword().trim()));
        }
        request.clauses().forEach(clause -> {
            var field = FIELD_MAPPINGS.get(clause.field());
            if (field != null && field.exactProperty() != null) {
                var values = exactValues(clause);
                if (!values.isEmpty()) {
                    exactMatches.add(new ExactMatch(field.exactProperty(), values));
                }
                return;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            if (field == null) {
                text.append(escape(clause.keyword().trim()));
                return;
            }
            text.append(render(field, clause.keyword().trim()));
        });
        return new QueryPlan(bounded(text.toString()), exactMatches);
    }

    /**
     * The renderer's backstop. Truncation is a last resort, not a policy: it changes what the caller
     * asked for, which is why the domain record rejects an oversized request first. A trailing lone
     * escape character is dropped, because half an escape makes the whole expression invalid.
     */
    private static String bounded(String text) {
        if (text.length() <= MAX_RENDERED_CLAUSE_CHARS) {
            return text;
        }
        var cut = text.substring(0, MAX_RENDERED_CLAUSE_CHARS);
        var backslashes = 0;
        for (var index = cut.length() - 1; index >= 0 && cut.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return backslashes % 2 == 1 ? cut.substring(0, cut.length() - 1) : cut;
    }

    /**
     * The normalized value list of one exact clause. Only an annotation-style URI gains the
     * {@link AnnotationUriResolver} candidate forms (MIRIAM URN to identifiers.org and back); the
     * separator fragments of {@link IndexText} are never applied here, because a fragment equality
     * would be a different value. Blank values are dropped and duplicates collapse.
     */
    private static List<String> exactValues(QueryClause clause) {
        var typed = clause.keyword() == null ? "" : clause.keyword().trim();
        var values = new LinkedHashSet<String>();
        addExactValue(values, typed);
        if (clause.field() == QueryField.URI) {
            var resolution = URI_RESOLVER.resolve(typed);
            if (resolution.resolvable()) {
                resolution.candidateUrls().forEach(candidate -> addExactValue(values, candidate));
            }
        }
        return List.copyOf(values);
    }

    private static void addExactValue(Set<String> values, String value) {
        var normalized = ExactText.lowercase(value);
        if (normalized != null && !normalized.isBlank()) {
            values.add(normalized);
        }
    }

    /**
     * The Lucene part of a request, without the exact predicates. Kept for the text-rendering tests:
     * it delegates to {@link #plan}, so it cannot drift from the production path.
     */
    static String from(ModelQueryRequest request) {
        return plan(request).textQuery();
    }

    /**
     * Renders a mapped clause. Single-property fields keep the historical shape
     * {@code property:(term)^boost}; multi-property fields (schema versions live on five separate
     * properties) become a grouped OR so one clause can match any of them.
     */
    private static String render(FieldMapping field, String value) {
        var term = term(value);
        if (field.properties().size() == 1) {
            return field.properties().getFirst() + ":(" + term + ")^" + field.weight();
        }
        var grouped = new StringBuilder("(");
        for (var property : field.properties()) {
            if (grouped.length() > 1) {
                grouped.append(" OR ");
            }
            grouped.append(property).append(":(").append(term).append(')');
        }
        return grouped.append(")^").append(field.weight()).toString();
    }

    /**
     * Renders one clause value. Values that resolve to an alternative annotation URI form (MIRIAM URNs
     * and identifiers.org URLs) become a grouped OR of every distinct form, so that indexed form of
     * the value matches regardless of which one the user typed. Values containing {@code _ : . -} also
     * gain the separator-split fragments the indexer writes (legacy {@code IndexText}), and both kinds
     * of expansion share one grouped OR.
     */
    private static String term(String value) {
        var fragments = expansionFragments(value);
        var forms = new LinkedHashSet<String>();
        forms.add(value);
        forms.addAll(fragments);
        var resolution = URI_RESOLVER.resolve(value);
        if (resolution.resolvable()) {
            forms.addAll(resolution.candidateUrls());
        }
        if (forms.size() == 1) {
            return escape(value);
        }
        var grouped = new StringBuilder("(");
        forms.forEach(form -> {
            if (grouped.length() > 1) {
                grouped.append(" OR ");
            }
            grouped.append(escape(form));
        });
        return grouped.append(')').toString();
    }

    /**
     * The separator-split fragments legacy {@code IndexText.expandTermsSpecialChars} adds to the
     * indexed aggregate text, so a query for one fragment finds the model whose identifier contained
     * the separator. Empty when the value has no separator or yields no usable fragment.
     */
    private static List<String> expansionFragments(String value) {
        return IndexText.termFragments(value);
    }

    private static String escape(String value) {
        var escaped = new StringBuilder();
        var clamped = IndexText.shrinkToMaxTermLength(value);
        for (var index = 0; index < clamped.length(); index++) {
            var character = clamped.charAt(index);
            if (isSpecialCharacter(character)) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static boolean isSpecialCharacter(char character) {
        return switch (character) {
            case '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/' -> true;
            default -> false;
        };
    }

    /** {@code exactProperty} names the normalized copy an equality clause matches, or is null. */
    private record FieldMapping(List<String> properties, int weight, String exactProperty) {
        FieldMapping(String property, int weight) {
            this(List.of(property), weight, null);
        }

        FieldMapping(String property, int weight, String exactProperty) {
            this(List.of(property), weight, exactProperty);
        }
    }
}

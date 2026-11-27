package org.masymos.neo4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.masymos.core.annotation.AnnotationUriResolver;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ExactText;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.QueryField;
import org.masymos.core.query.QueryPlan;

final class Neo4jResourceFulltextQuery {
    private static final AnnotationUriResolver URI_RESOLVER = new AnnotationUriResolver();

    private static final Map<QueryField, FieldMapping> ANNOTATION_FIELDS = Map.of(
            QueryField.RESOURCE_URI, new FieldMapping("uri", "uriExact", false),
            QueryField.URI, new FieldMapping("uri", "uriExact", false),
            QueryField.RESOURCE_TEXT, new FieldMapping("resourceText", null, false),
            QueryField.NON_RDF, new FieldMapping("nonRdf", null, false)
    );
    private static final Map<QueryField, FieldMapping> PERSON_FIELDS = Map.of(
            QueryField.GIVENNAME, new FieldMapping("givenName", "givenNameExact", true),
            QueryField.FAMILYNAME, new FieldMapping("familyName", "familyNameExact", true),
            QueryField.EMAIL, new FieldMapping("email", null, false),
            QueryField.ORGANIZATION, new FieldMapping("organization", null, false)
    );
    private static final Map<QueryField, FieldMapping> PUBLICATION_FIELDS = Map.of(
            QueryField.TITLE, new FieldMapping("title", null, false),
            QueryField.JOURNAL, new FieldMapping("journal", null, false),
            QueryField.ABSTRACT, new FieldMapping("abstractText", null, false),
            QueryField.AFFILIATION, new FieldMapping("affiliation", null, false),
            QueryField.YEAR, new FieldMapping("year", "yearExact", true),
            QueryField.AUTHOR, new FieldMapping("author", null, false),
            QueryField.ID, new FieldMapping("pubId", "pubIdExact", true)
    );

    private Neo4jResourceFulltextQuery() {}

    static QueryPlan annotations(ModelQueryRequest request) {
        return from(request, ANNOTATION_FIELDS);
    }

    static QueryPlan persons(ModelQueryRequest request) {
        return from(request, PERSON_FIELDS);
    }

    static QueryPlan publications(ModelQueryRequest request) {
        return from(request, PUBLICATION_FIELDS);
    }

    /**
     * Splits a request into the Lucene expression for text clauses and one equality predicate per exact
     * field. The Cypher builders combine the two parts with AND.
     */
    private static QueryPlan from(ModelQueryRequest request, Map<QueryField, FieldMapping> fieldMappings) {
        var text = new StringBuilder();
        var exactValues = new LinkedHashMap<String, LinkedHashSet<String>>();
        if (request.keyword() != null && !request.keyword().isBlank()) {
            appendText(text, escape(request.keyword().trim()));
        }
        request.clauses().forEach(clause -> {
            if (clause.field() == QueryField.KEYWORD) {
                appendText(text, escape(clause.keyword().trim()));
                return;
            }
            var field = fieldMappings.get(clause.field());
            if (field == null) {
                appendText(text, escape(clause.keyword().trim()));
                return;
            }
            if (field.exactProperty() == null) {
                appendText(text, field.property() + ":(" + escape(clause.keyword().trim()) + ")");
                return;
            }
            exactValues
                    .computeIfAbsent(field.exactProperty(), property -> new LinkedHashSet<>())
                    .addAll(exactValues(clause.field(), field, clause.keyword()));
        });
        var exactMatches = new ArrayList<ExactMatch>();
        exactValues.forEach((property, values) -> {
            values.removeIf(value -> value == null || value.isBlank());
            if (!values.isEmpty()) {
                exactMatches.add(new ExactMatch(property, List.copyOf(values)));
            }
        });
        return new QueryPlan(text.toString(), exactMatches);
    }

    /**
     * The normalized values an exact clause may equal. Annotation URIs are case preserved and a MIRIAM
     * URN or identifiers.org URL also accepts every alternative form the resolver knows, because the
     * stored URI may be written in either shape. Separator fragments are never added: a fragment
     * equality would be a different value.
     */
    private static List<String> exactValues(QueryField queryField, FieldMapping field, String clauseValue) {
        var forms = new LinkedHashSet<String>();
        forms.add(normalize(field, clauseValue));
        if (queryField == QueryField.RESOURCE_URI || queryField == QueryField.URI) {
            var resolution = URI_RESOLVER.resolve(clauseValue.trim());
            if (resolution.resolvable()) {
                resolution.candidateUrls().forEach(candidate -> forms.add(normalize(field, candidate)));
            }
        }
        return List.copyOf(forms);
    }

    private static String normalize(FieldMapping field, String value) {
        return field.lowercased() ? ExactText.lowercase(value) : ExactText.identity(value);
    }

    private static void appendText(StringBuilder text, String rendered) {
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(rendered);
    }

    private static String escape(String value) {
        var escaped = new StringBuilder();
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
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

    /**
     * A query field of one entity: the full-text property, the exact-match property ({@code null} for
     * text-only fields) and whether the exact copy is lowercased.
     */
    private record FieldMapping(String property, String exactProperty, boolean lowercased) {}
}

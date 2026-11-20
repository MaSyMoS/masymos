package org.masymos.core.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The OLS4 term provider: one {@code GET} per class and two per property, because the type of an entity
 * is answered by "does the class path exist" and the 404 decides.
 *
 * <p><strong>What is asked, and why.</strong>
 *
 * <ol>
 *   <li><em>Is this IRI a class?</em> {@code {base}/v2/ontologies/{catalogue ontology}/classes/
 *       <double-encoded iri>}. {@code catalogue ontology} is {@link
 *       AnnotationPrefixCatalogue#ols4Ontology(TermKey)}, measured correct for all five prefixes; no
 *       ontology is guessed and no search is made. A 2xx body is parsed and answered.
 *   <li><em>Otherwise, is it a property?</em> Only on a 404 from the class call: {@code
 *       {base}/v2/ontologies/{same ontology}/properties/<same IRI>}. A 2xx body is parsed and answered; a
 *       404 there is the definitive negative {@code "OLS4: no such class or property"}.
 * </ol>
 *
 * <p><strong>Why the search is gone.</strong> The previous version learned the entity's type from
 * {@code {base}/search?q=<iri>&exact=true&queryFields=iri}, which is not an exact oracle. Measured on
 * 2026-11-14: {@code GO_0008150} returned {@code numFound:124}, because the same IRI appears in many
 * imported ontologies, and {@code GO_0043241} returned {@code numFound:0} although {@code
 * /v2/ontologies/go/classes/<iri>} answered 200 for it. A real entity was therefore silently turned into
 * "no such entity". The two captures {@code ols4-search-go-0008150.json} and {@code
 * ols4-search-go-0043241.json} are kept as the evidence for that measurement. The class-then-property
 * fallback discriminates cleanly instead: one request for a class (the common case), two for a property.
 *
 * <p><strong>What it costs.</strong> A class: exactly one request. A property: exactly two, the class
 * 404 and the property 200. A missing entity: exactly two, the class 404 and the property 404.
 *
 * <p>OLS4 needs no key: this provider sends no {@code Authorization} header and reads none.
 *
 * <p>Outcome classification:
 *
 * <ul>
 *   <li>Both the class call and the property call answer 404: {@link
 *       TermDefinition#unresolved(TermKey, TermDefinition.FailureClass, String) unresolved} with {@link
 *       TermDefinition.FailureClass#DEFINITIVE}. OLS4 answered about this IRI and has neither entity.
 *   <li>A 2xx body with a label becomes {@link TermDefinition#resolved(TermKey, String,
 *       TermDefinition.Source) resolved} with the keyword text below.
 *   <li>A 2xx body without a label <em>throws</em>: an entity OLS4 serves must have one.
 *   <li>Every other failure — a transport failure, any status other than 2xx or 404, a body that is not
 *       the expected JSON — <em>throws</em> {@link IllegalStateException}, and the resolver demotes the
 *       provider for the rest of the run. A 500 on either call is a provider fault, never a definitive
 *       negative.
 * </ul>
 *
 * <p><strong>Which types are served.</strong> {@link #supports(TermKey)} is true for a prefix whose
 * catalogue class IRI and OLS4 ontology id are both known; all five ontological prefixes are served
 * ({@code KISAO_0000019} answers as a class, {@code KISAO_0000245} as a property through the fallback).
 *
 * <p><strong>Obsolete and merged ids (D11).</strong> A curated model may cite a term that has since
 * been obsoleted or merged. Measured 2026-11-16 on OLS4 v2: such a body answers 200 and carries the
 * markers as <em>flat, top-level, IRI-keyed</em> fields ({@code isObsolete},
 * {@code http://www.w3.org/2002/07/owl#deprecated}, {@code
 * http://purl.obolibrary.org/obo/IAO_0100001} = the successor IRI, {@code
 * http://purl.obolibrary.org/obo/IAO_0000231} = the reason IRI); there is no {@code annotation} object.
 * The rules:
 *
 * <ul>
 *   <li><em>Not obsolete:</em> {@link TermStatus#none()}, nothing else changes.
 *   <li><em>Obsolete with a successor:</em> the successor IRI is looked up through the <em>same</em>
 *       path (class, then property on a 404, same double encoding). The keyword text is the cited
 *       term's own label, definitions and synonyms <em>followed by</em> the successor's, in one
 *       de-duplicated list (D12): the citation's words and the successor's meaning are both
 *       searchable, which is the point of annotating with the cited id rather than rewriting it. The
 *       returned key stays the cited key.
 *   <li><em>Successor lookup 404s or fails:</em> the cited body's own text is indexed and the status is
 *       still attached. The result is {@code resolved}, never a definitive negative: "never cache it as
 *       a failure".
 *   <li><em>Obsolete without a successor:</em> the cited term keeps its own text.
 * </ul>
 *
 * <p><strong>The keyword text</strong> is label, definitions, names, in that order, through the same
 * whitespace collapsing, case-insensitive de-duplication and length cap for every source:
 *
 * <ul>
 *   <li>label: {@code label} (a string or a one-element array in the measured bodies).
 *   <li>definitions: {@code definition}, then {@code skos:definition}, then {@code IAO_0000115}, each
 *       a string or an object carrying a {@code value}.
 *   <li>names: {@code synonym[]} (strings), then {@code skos:altLabel[].value} (objects).
 * </ul>
 *
 * <p>The definition sources are three because the measured bodies carry all three: {@code
 * GO_0008150} has a {@code definition} (the rdfs:comment) and an {@code IAO_0000115} object with the
 * second definition, {@code KISAO_0000019} and {@code KISAO_0000245} have only {@code
 * skos:definition}. Reading one key would silently drop a definition that the old v1 read produced.
 */
public final class Ols4TermProvider implements AnnotationTermProvider {

    private static final String V2_PATH = "/v2/ontologies/";
    private static final String NO_SUCH_ENTITY = "OLS4: no such class or property";
    private static final String CLASS_FAILED = "OLS4 class lookup failed: ";
    private static final String PROPERTY_FAILED = "OLS4 property lookup failed: ";

    private static final String LABEL_FIELD = "label";
    private static final String DEFINITION_FIELD = "definition";
    private static final String SKOS_DEFINITION = "http://www.w3.org/2004/02/skos/core#definition";
    private static final String IAO_DEFINITION = "http://purl.obolibrary.org/obo/IAO_0000115";
    private static final String SYNONYM_FIELD = "synonym";
    private static final String SKOS_ALT_LABEL = "http://www.w3.org/2004/02/skos/core#altLabel";
    private static final String VALUE_FIELD = "value";

    /** The OBO purl prefix a successor IRI is written under; {@link #curieOf(String)} strips it. */
    private static final String OBO_PURL_PREFIX = "http://purl.obolibrary.org/obo/";

    /** The lifecycle markers of a v2 body: flat, top-level, IRI-keyed (measured 2026-11-16). */
    private static final String OBSOLETE_FIELD = "isObsolete";
    private static final String DEPRECATED_FIELD = "http://www.w3.org/2002/07/owl#deprecated";
    private static final String REPLACED_BY_FIELD = "http://purl.obolibrary.org/obo/IAO_0100001";
    private static final String OBSOLESCENCE_REASON_FIELD = "http://purl.obolibrary.org/obo/IAO_0000231";

    /** The name this provider reports as the source of a {@link TermStatus}. */
    private static final String STATUS_SOURCE = "ols4";

    /** The status that says "OLS4 has no entity of this kind at this IRI", and so asks for the next kind. */
    private static final int NOT_FOUND = 404;

    private final String baseUrl;
    private final AnnotationProviderHttp http;
    private final AnnotationPrefixCatalogue catalogue;
    private final int maxTextLength;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Ols4TermProvider(
            String baseUrl,
            AnnotationProviderHttp http,
            AnnotationPrefixCatalogue catalogue,
            int maxTextLength
    ) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.http = Objects.requireNonNull(http, "http");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.maxTextLength = maxTextLength < 1
                ? AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH
                : maxTextLength;
    }

    @Override
    public TermDefinition.Source source() {
        return TermDefinition.Source.OLS4;
    }

    /** True exactly when the catalogue knows both an OLS4 ontology id and a class IRI for the prefix. */
    @Override
    public boolean supports(TermKey key) {
        if (key == null) {
            return false;
        }
        return catalogue.ols4Ontology(key).isPresent() && catalogue.classIri(key).isPresent();
    }

    /**
     * The detail URL for an entity, before any request is sent. Pure. The IRI is encoded twice, so its
     * slashes, colon and hash stay inside one path segment.
     */
    public String detailUrl(String ols4Ontology, String entityIri, boolean property) {
        return baseUrl + V2_PATH + encode(ols4Ontology) + (property ? "/properties/" : "/classes/")
                + doubleEncode(entityIri);
    }

    @Override
    public Map<TermKey, TermDefinition> resolveAll(Collection<TermKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        var answered = new LinkedHashMap<TermKey, TermDefinition>();
        for (var key : keys) {
            if (key == null || answered.containsKey(key)) {
                continue;
            }
            var catalogueOntology = catalogue.ols4Ontology(key).orElse(null);
            var iri = catalogue.classIri(key).orElse(null);
            if (catalogueOntology == null || iri == null) {
                continue;
            }
            answered.put(key, lookup(key, catalogueOntology, iri));
        }
        return answered;
    }

    /**
     * Ask for a class, and on its 404 ask for a property. Only the two 404s together are a definitive
     * negative; any other non-2xx status throws.
     */
    private TermDefinition lookup(TermKey key, String catalogueOntology, String iri) {
        var answered = findEntity(catalogueOntology, iri);
        if (answered == null) {
            return TermDefinition.unresolved(key, TermDefinition.FailureClass.DEFINITIVE, NO_SUCH_ENTITY);
        }
        return resolved(key, answered.body(), answered.url(), catalogueOntology);
    }

    /**
     * One entity, class first and property on a 404, or {@code null} when OLS4 has neither. Shared by
     * the cited lookup and the successor lookup, so a successor is asked the same question (including
     * the double encoding) as any other id.
     */
    private Answer findEntity(String catalogueOntology, String iri) {
        var classUrl = detailUrl(catalogueOntology, iri, false);
        var classBody = readJsonUnlessNotFound(classUrl, CLASS_FAILED);
        if (classBody != null) {
            return new Answer(classBody, classUrl);
        }
        var propertyUrl = detailUrl(catalogueOntology, iri, true);
        var propertyBody = readJsonUnlessNotFound(propertyUrl, PROPERTY_FAILED);
        if (propertyBody != null) {
            return new Answer(propertyBody, propertyUrl);
        }
        return null;
    }

    /**
     * The body of a served entity with its lifecycle status, or a throw when it carries no label.
     *
     * <p>A merged id indexes <em>both</em> texts under the cited key (D12): its own published text
     * first, then the successor's, in one de-duplicated list. The returned key stays the cited key, and
     * a successor that cannot be read simply contributes nothing — it never turns a served term into a
     * negative.
     */
    private TermDefinition resolved(TermKey key, JsonNode root, String url, String catalogueOntology) {
        var label = firstValue(root.get(LABEL_FIELD));
        if (label == null) {
            throw new IllegalStateException("OLS4 entity carried no label: " + url);
        }
        var obsolete = isTrue(root.get(OBSOLETE_FIELD)) || isTrue(root.get(DEPRECATED_FIELD));
        if (!obsolete) {
            return TermDefinition.resolved(key, keywordText(root, label), TermDefinition.Source.OLS4);
        }

        var successorIri = firstValue(root.get(REPLACED_BY_FIELD));
        var reason = firstValue(root.get(OBSOLESCENCE_REASON_FIELD));
        // D12: what the citation says, then what it means. A curated model citing GO:0000003 is
        // annotated with "reproduction", and a search for a word of that obsolete definition
        // ("individuals", "inherited") must still find the model; the successor's text is added
        // because the cited id also means the successor's thing. One de-duplicated list and one cap,
        // because the two texts overlap - GO:0000003 and GO:0022414 share "individuals".
        var values = new LinkedHashSet<String>();
        var seen = new LinkedHashSet<String>();
        collectKeywordText(values, seen, root, label);
        if (successorIri != null) {
            collectSuccessorText(catalogueOntology, successorIri, values, seen);
        }
        var status = new TermStatus(true, curieOf(successorIri), reason, STATUS_SOURCE);
        return TermDefinition.resolved(key, cap(values), TermDefinition.Source.OLS4, status);
    }

    /**
     * Adds a successor's published text to the same de-duplicated list.
     *
     * <p>An unreadable successor adds nothing: OLS4 has no class or property at that IRI, or the
     * request fails at transport level. A missing successor is not a failure of the cited term, so it
     * never propagates — the cited text is still indexed, which is the whole point of D12.
     */
    private void collectSuccessorText(
            String catalogueOntology, String successorIri, Set<String> values, Set<String> seen) {
        Answer answered;
        try {
            answered = findEntity(catalogueOntology, successorIri);
        } catch (RuntimeException exception) {
            return;
        }
        if (answered == null) {
            return;
        }
        var label = firstValue(answered.body().get(LABEL_FIELD));
        if (label != null) {
            collectKeywordText(values, seen, answered.body(), label);
        }
    }

    /**
     * The curie form of an OBO purl IRI: {@code http://purl.obolibrary.org/obo/GO_0032984} becomes
     * {@code GO:0032984}. Anything else is returned unchanged, because it is already what the provider
     * named; {@code null} stays {@code null}.
     */
    static String curieOf(String iri) {
        if (iri == null || !iri.startsWith(OBO_PURL_PREFIX)) {
            return iri;
        }
        var local = iri.substring(OBO_PURL_PREFIX.length());
        var separator = local.indexOf('_');
        if (separator <= 0 || separator == local.length() - 1) {
            return iri;
        }
        return local.substring(0, separator) + ":" + local.substring(separator + 1);
    }

    /** Whether a v2 marker is true, as a boolean or as the string spelling of one. */
    private static boolean isTrue(JsonNode marker) {
        var value = firstValue(marker);
        return value != null && value.trim().equalsIgnoreCase("true");
    }

    /** One served entity: its parsed body and the URL that answered it. */
    private record Answer(JsonNode body, String url) {}

    // ---- shared JSON reading -------------------------------------------------------------------

    /**
     * The body of a 2xx answer as a JSON object, or {@code null} for a 404. Every other outcome — no
     * response, a status that is neither 2xx nor 404, an empty body, a body that is not a JSON object —
     * throws, so the resolver demotes this provider instead of recording a negative.
     */
    private JsonNode readJsonUnlessNotFound(String url, String failurePrefix) {
        var request = AnnotationProviderHttp.ProviderRequest.get(url, Map.of("Accept", "application/json"));
        var response = http.send(request);
        if (response == null) {
            throw new IllegalStateException(failurePrefix + "no response: " + request);
        }
        if (response.status() == NOT_FOUND) {
            return null;
        }
        if (!response.successful()) {
            throw new IllegalStateException(failurePrefix + "HTTP status " + response.status() + ": " + request);
        }
        var body = response.body();
        if (body == null || body.isBlank()) {
            throw new IllegalStateException(failurePrefix + "empty body: " + request);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(failurePrefix + "body is not JSON: "
                    + exception.getClass().getSimpleName() + ": " + request);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(failurePrefix + "body is not a JSON object: " + request);
        }
        return root;
    }

    // ---- keyword text ---------------------------------------------------------------------------

    /** label, then the definitions, then the names; de-duplicated, newline-joined, capped. */
    private String keywordText(JsonNode node, String label) {
        var values = new LinkedHashSet<String>();
        collectKeywordText(values, new LinkedHashSet<String>(), node, label);
        return cap(values);
    }

    /**
     * Appends one entity's published text — label, then definitions, then names — to {@code values},
     * skipping anything already seen.
     *
     * <p>Separate from {@link #keywordText} so a merged id can run the cited term and its successor
     * through <em>one</em> list and one case-insensitive set (D12): the two vocabularies overlap
     * ("individuals", "genetic material" appear in both GO:0000003 and GO:0022414), and a duplicate
     * would inflate the term's weight in the index for no reason.
     */
    private static void collectKeywordText(Set<String> values, Set<String> seen, JsonNode node, String label) {
        addText(values, seen, label);
        addDefinitions(node, DEFINITION_FIELD, values, seen);
        addDefinitions(node, SKOS_DEFINITION, values, seen);
        addDefinitions(node, IAO_DEFINITION, values, seen);
        addAll(node, SYNONYM_FIELD, values, seen);
        addAll(node, SKOS_ALT_LABEL, values, seen);
    }

    /**
     * One definition source. A value carrying SBO's synonym stub is a name, not a definition (see
     * {@link SynonymComment}), so it contributes the synonym behind the stub and not the stub word.
     * A plain value is a definition.
     */
    private static void addDefinitions(
            JsonNode node, String field, Set<String> values, Set<String> seen) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(element -> addDefinition(element, values, seen));
        } else {
            addDefinition(value, values, seen);
        }
    }

    private static void addDefinition(JsonNode element, Set<String> values, Set<String> seen) {
        var raw = firstValue(element);
        var synonym = SynonymComment.synonym(raw);
        addText(values, seen, synonym == null ? raw : synonym);
    }

    private String cap(Set<String> values) {
        var builder = new StringBuilder();
        for (var line : values) {
            var extra = builder.isEmpty() ? line.length() : line.length() + 1;
            if (builder.length() + extra > maxTextLength) {
                if (builder.isEmpty() && maxTextLength > 0) {
                    builder.append(line, 0, maxTextLength);
                }
                break;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static void addAll(JsonNode node, String field, Set<String> values, Set<String> seen) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isArray()) {
            value.forEach(element -> addOne(element, values, seen));
        } else {
            addOne(value, values, seen);
        }
    }

    /** One value: a scalar, or an object carrying {@code value} (the skos altLabel and IAO shape). */
    private static void addOne(JsonNode element, Set<String> values, Set<String> seen) {
        addText(values, seen, firstValue(element));
    }

    private static void addText(Set<String> values, Set<String> seen, String raw) {
        if (raw == null) {
            return;
        }
        var normalized = raw.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return;
        }
        if (seen.add(normalized.toLowerCase(Locale.ROOT))) {
            values.add(normalized);
        }
    }

    /**
     * The text of one value: a scalar as is, an object's {@code value} field, the first such text of an
     * array. {@code null} when there is none.
     */
    private static String firstValue(JsonNode element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (element.isValueNode()) {
            return element.asText();
        }
        if (element.isObject()) {
            return firstValue(element.get(VALUE_FIELD));
        }
        if (element.isArray()) {
            for (var item : element) {
                var text = firstValue(item);
                if (text != null) {
                    return text;
                }
            }
            return null;
        }
        return null;
    }

    // ---- encoding -------------------------------------------------------------------------------

    /** Encodes an IRI twice, so its slashes, colon and hash stay inside one path segment. */
    static String doubleEncode(String value) {
        return encode(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlash(String baseUrl) {
        var trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

package org.masymos.core.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The BioPortal term provider: one {@code POST /batch} per chunk of keys.
 *
 * <p>Everything below was measured on 2026-11-13 against {@code https://data.bioontology.org} and
 * agrees with the published API documentation; the earlier version of this class guessed the wire
 * format and guessed wrong in three places. The committed answer is {@code
 * src/test/resources/annotation/providers/bioportal-batch.json} (28277 bytes, sidecar beside it).
 *
 * <ul>
 *   <li><b>Credential.</b> The header is {@code Authorization: apikey token=<key>}. The bare {@code
 *       apikey <key>} form answers HTTP 401 for every request; the key itself is not restricted.
 *   </li>
 *   <li><b>Request.</b> {@code POST {base}/batch} — not {@code /ontologies/batch}, and {@code
 *       display} belongs in the body, not in a query parameter. The body is keyed by media type,
 *       {@code {"http://www.w3.org/2002/07/owl#Class": {"collection": [{"class": <iri>, "ontology":
 *       <acronym>}, ...], "display": "prefLabel,definition,synonym"}}}.
 *   </li>
 *   <li><b>Ontology id.</b> BioPortal matches it as an exact string and its canonical form is {@code
 *       http}, not the {@code https} the base URL would suggest: {@code
 *       http://data.bioontology.org/ontologies/GO} answers the class, {@code
 *       https://data.bioontology.org/ontologies/GO} answers HTTP 200 with an empty collection, and
 *       {@code GET /ontologies/GO} advertises the {@code https} form as its {@code @id} although that
 *       form does not work. The id is therefore built from the base URL's authority with the scheme
 *       forced to {@code http} (see {@link #ontologyId}).
 *   </li>
 *   <li><b>Response.</b> {@code {"http://www.w3.org/2002/07/owl#Class": [<node>, ...]}}, each node
 *       {@code {"prefLabel": ..., "synonym": [...], "definition": [...], "@id": <class iri>}}.
 *       {@code definition} and {@code synonym} arrive as <em>top-level arrays</em>, not as the {@code
 *       annotation} block other services use; the KISAO alternative labels are among those synonyms
 *       ({@code CVODE} plus {@code VODEPK}, {@code VODE}, "code value ordinary differential equation
 *       solver", together with the definition), unlike OLS4, where the same labels sit in {@code
 *       annotation}.
 *   </li>
 * </ul>
 *
 * <p>The key travels in that header and never in a URL, a message, a log line or an exception: a query
 * parameter would land in log lines and exception messages. Every request the provider mentions in a
 * failure is rendered through {@link AnnotationProviderHttp.ProviderRequest#redacted(Collection)}, so
 * the header value is replaced before it can reach a message.
 *
 * <p>Outcome classification:
 *
 * <ul>
 *   <li>A class answer becomes {@link TermDefinition#resolved(TermKey, String,
 *       TermDefinition.Source) resolved} with prefLabel, definition and synonyms joined by newlines,
 *       de-duplicated case-insensitively and capped at the maximum text length.
 *   <li>An answer whose {@code @id} names an IRI nobody asked for cannot be attributed to a key and is
 *       skipped; its key stays unclaimed and travels on to the next provider.
 *   <li>An <em>empty collection</em> — or a class that was asked for and not answered at all, as with
 *       {@code KISAO_0000245}, for which BioPortal answers 404 just as OLS4 does — is <em>not</em> a
 *       failure. Nothing was learned, so the keys stay unclaimed and the resolver asks OLS4.
 *   <li>A transport failure, a non-2xx batch response, a body that is not the expected JSON shape or a
 *       class answer without any text <em>throws</em> {@link IllegalStateException}. The resolver
 *       demotes the provider for the rest of the run on a throw and leaves the keys for the next
 *       provider and the page fallback; a per-class complaint would silently turn an outage into
 *       knowledge.
 * </ul>
 *
 * <p>The class IRI comes from {@link AnnotationPrefixCatalogue#classIri(TermKey)} (one column, shared
 * with OLS4) and the acronym from {@link AnnotationPrefixCatalogue#bioportalOntology(TermKey)}. The
 * parsing is deliberately tolerant of list-versus-scalar for the text fields, because a field is a
 * shape, not a contract, once the real answer is committed.
 */
public final class BioPortalTermProvider implements AnnotationTermProvider {

    /** The path appended to the configured base URL: the batch endpoint, not {@code /ontologies/batch}. */
    public static final String BATCH_PATH = "/batch";

    /** The header carrying the API key. The key is never put in the URL. */
    public static final String API_KEY_HEADER = "Authorization";

    /** What BioPortal expects before the key: the scheme word and the {@code token=} pair. */
    public static final String API_KEY_PREFIX = "apikey token=";

    /** The media type the batch body is keyed by, and the one the answer is keyed by. */
    public static final String CLASS_MEDIA_TYPE = "http://www.w3.org/2002/07/owl#Class";

    /** The fields {@code display} asks for: the prefLabel, the definitions and every synonym. */
    public static final String DISPLAY = "prefLabel,definition,synonym";

    /** The default chunk size: the design's default for {@code providers.batch.max}. */
    public static final int DEFAULT_MAX_BATCH = 20;

    private static final String[] LABEL_FIELDS = {"prefLabel", "label"};

    private final String baseUrl;
    private final Optional<String> apiKey;
    private final int maxBatch;
    private final AnnotationProviderHttp http;
    private final AnnotationPrefixCatalogue catalogue;
    private final int maxTextLength;
    private final Set<String> secretHeaders;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BioPortalTermProvider(
            String baseUrl,
            String apiKey,
            int maxBatch,
            AnnotationProviderHttp http,
            AnnotationPrefixCatalogue catalogue,
            int maxTextLength
    ) {
        this(baseUrl, apiKey, maxBatch, http, catalogue, maxTextLength, Set.of(API_KEY_HEADER));
    }

    public BioPortalTermProvider(
            String baseUrl,
            String apiKey,
            int maxBatch,
            AnnotationProviderHttp http,
            AnnotationPrefixCatalogue catalogue,
            int maxTextLength,
            Collection<String> secretHeaders
    ) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.apiKey = Optional.ofNullable(apiKey).map(String::trim).filter(key -> !key.isEmpty());
        this.maxBatch = maxBatch < 1 ? DEFAULT_MAX_BATCH : maxBatch;
        this.http = Objects.requireNonNull(http, "http");
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.maxTextLength = maxTextLength < 1
                ? AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH
                : maxTextLength;
        this.secretHeaders = secretHeaders == null ? Set.of(API_KEY_HEADER) : Set.copyOf(secretHeaders);
    }

    /** The chunk size this provider uses: one request per chunk, never per term. */
    public int maxBatch() {
        return maxBatch;
    }

    @Override
    public TermDefinition.Source source() {
        return TermDefinition.Source.BIOPORTAL;
    }

    /**
     * True exactly when the catalogue has a BioPortal ontology for the key's prefix. KISAO has one —
     * BioPortal serves KISAO — while the page-only prefixes and unknown prefixes do not.
     */
    @Override
    public boolean supports(TermKey key) {
        if (key == null) {
            return false;
        }
        return catalogue.bioportalOntology(key).isPresent();
    }

    /**
     * Resolves the keys this provider serves, one request per chunk of {@link #maxBatch()}.
     *
     * <p>Keys it does not serve are not asked about and are not in the result; the resolver supplies
     * them from the next provider. Keys BioPortal answers nothing about — an empty collection, or no
     * entry in the batch answer — are not in the result either and travel on to OLS4. Any failure of a
     * chunk throws, and the resolver demotes this provider for the rest of the run.
     */
    @Override
    public Map<TermKey, TermDefinition> resolveAll(Collection<TermKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        var served = new LinkedHashMap<TermKey, String>();
        for (var key : keys) {
            if (key == null || served.containsKey(key)) {
                continue;
            }
            var ontology = ontologyFor(key);
            if (ontology != null) {
                served.put(key, ontology);
            }
        }
        if (served.isEmpty()) {
            return Map.of();
        }

        var answered = new LinkedHashMap<TermKey, TermDefinition>();
        var servedKeys = new ArrayList<>(served.keySet());
        for (var start = 0; start < servedKeys.size(); start += maxBatch) {
            var chunk = servedKeys.subList(start, Math.min(start + maxBatch, servedKeys.size()));
            answered.putAll(sendChunk(chunk, served));
        }
        return answered;
    }

    // ---- one chunk ---------------------------------------------------------------------------

    private Map<TermKey, TermDefinition> sendChunk(List<TermKey> chunk, Map<TermKey, String> ontologyOf) {
        var iriToKey = new LinkedHashMap<String, TermKey>();
        var iriToOntology = new LinkedHashMap<String, String>();
        for (var key : chunk) {
            var iri = classIriFor(key);
            iriToKey.put(iri, key);
            iriToOntology.put(iri, ontologyId(ontologyOf.get(key)));
        }

        var request = requestFor(iriToOntology);
        var response = http.send(request);
        if (response == null) {
            throw new IllegalStateException("BioPortal batch failed: no response: " + safeUri(request));
        }
        if (!response.successful()) {
            throw new IllegalStateException(
                    "BioPortal batch failed: HTTP status " + response.status() + ": " + safeUri(request));
        }

        var classes = parseBatchBody(response.body(), request);

        var answered = new LinkedHashMap<TermKey, TermDefinition>();
        var unclaimed = new LinkedHashMap<>(iriToKey);
        for (var node : classes) {
            var key = claim(node, unclaimed);
            if (key == null) {
                continue;
            }
            unclaimed.remove(key.wire());
            answered.put(key, definitionFor(key, node));
        }
        // An empty collection, or a class with no entry, is not "no such class": nothing was learned
        // about it, so the key stays unclaimed and the next provider gets its turn.
        return answered;
    }

    private AnnotationProviderHttp.ProviderRequest requestFor(Map<String, String> iriToOntology) {
        var collection = new ArrayList<Map<String, String>>();
        iriToOntology.forEach((iri, ontology) -> {
            var entry = new LinkedHashMap<String, String>();
            entry.put("class", iri);
            entry.put("ontology", ontology);
            collection.add(entry);
        });
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("collection", collection);
        parameters.put("display", DISPLAY);
        var bodyByMediaType = new LinkedHashMap<String, Object>();
        bodyByMediaType.put(CLASS_MEDIA_TYPE, parameters);

        String body;
        try {
            body = objectMapper.writeValueAsString(bodyByMediaType);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("BioPortal batch failed: request body could not be encoded: "
                    + exception.getClass().getSimpleName());
        }

        var headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        apiKey.ifPresent(key -> headers.put(API_KEY_HEADER, API_KEY_PREFIX + key));
        return new AnnotationProviderHttp.ProviderRequest(
                "POST", baseUrl + BATCH_PATH, headers, body);
    }

    /**
     * The class nodes of the answer. The body is keyed by media type and the value is a list of class
     * nodes; a media-type value that is a single object is accepted for tolerance, but a value that is
     * neither is a shape failure.
     */
    private List<JsonNode> parseBatchBody(String body, AnnotationProviderHttp.ProviderRequest request) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("BioPortal batch failed: empty body: " + safeUri(request));
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (java.io.IOException | RuntimeException exception) {
            throw new IllegalStateException("BioPortal batch failed: body is not JSON: "
                    + exception.getClass().getSimpleName() + ": " + safeUri(request));
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("BioPortal batch failed: body is not a JSON object: "
                    + safeUri(request));
        }

        var classes = new ArrayList<JsonNode>();
        root.fields().forEachRemaining(entry -> {
            var value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            if (value.isArray()) {
                value.forEach(classes::add);
            } else if (value.isObject()) {
                classes.add(value);
            } else {
                throw new IllegalStateException("BioPortal batch failed: answer for "
                        + entry.getKey() + " is not a list or an object: " + safeUri(request));
            }
        });
        return classes;
    }

    /**
     * The key a class answer belongs to: the class IRI in its {@code @id}, provided that IRI was asked
     * for. An answer that names an IRI nobody asked for cannot be attributed and is skipped; its key
     * stays unclaimed and travels on to the next provider.
     */
    private TermKey claim(JsonNode node, Map<String, TermKey> unclaimed) {
        if (node == null || !node.isObject()) {
            return null;
        }
        var id = text(node, "@id");
        return id == null ? null : unclaimed.get(id);
    }

    private TermDefinition definitionFor(TermKey key, JsonNode node) {
        var text = keywordText(node);
        if (text.isBlank()) {
            // The provider answered, and there is nothing to index: not knowledge, so not cached as
            // such, and not silently invented either.
            throw new IllegalStateException("BioPortal batch failed: class answer carried no text: "
                    + key.wire());
        }
        return TermDefinition.resolved(key, text, TermDefinition.Source.BIOPORTAL);
    }

    // ---- keyword text ------------------------------------------------------------------------

    /**
     * prefLabel, then every definition, then every synonym: the order the committed capture shows for
     * every answered class. The text fields are top-level arrays in BioPortal's answer;
     * de-duplicated case-insensitively, newline-joined, capped.
     */
    private String keywordText(JsonNode node) {
        var values = new LinkedHashSet<String>();
        var seen = new LinkedHashSet<String>();
        for (var field : LABEL_FIELDS) {
            addAll(node, field, values, seen);
        }
        addDefinitions(node, "definition", values, seen);
        addAll(node, "synonym", values, seen);

        var lines = new ArrayList<String>(values);
        var builder = new StringBuilder();
        for (var line : lines) {
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

    /**
     * One definition source. SBO's synonym stub lives in the same field as a definition and is
     * measured on the committed batch ({"Synonym: Kmp"} for {@code SBO:0000323}); it is a name, not a
     * definition, and the stub word is not text. See {@link SynonymComment}.
     */
    private static void addDefinitions(JsonNode node, String field, Set<String> values, Set<String> seen) {
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
        if (element == null || element.isNull() || !element.isValueNode()) {
            return;
        }
        var raw = element.asText();
        var synonym = SynonymComment.synonym(raw);
        addText(values, seen, synonym == null ? raw : synonym);
    }

    private static void addOne(JsonNode element, Set<String> values, Set<String> seen) {
        if (element == null || element.isNull()) {
            return;
        }
        addText(values, seen, element.isValueNode() ? element.asText() : null);
    }

    private static void addText(Set<String> values, Set<String> seen, String text) {
        if (text == null) {
            return;
        }
        var normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return;
        }
        if (seen.add(normalized.toLowerCase(Locale.ROOT))) {
            values.add(normalized);
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        var asText = value.asText().trim();
        return asText.isEmpty() ? null : asText;
    }

    // ---- catalogue ---------------------------------------------------------------------------

    private String ontologyFor(TermKey key) {
        return catalogue.bioportalOntology(key).orElse(null);
    }

    private String classIriFor(TermKey key) {
        return catalogue.classIri(key).orElseThrow(() -> new IllegalArgumentException(
                "No BioPortal class IRI in the prefix catalogue for " + key.wire()));
    }

    /**
     * The BioPortal ontology id for an acronym: {@code http://<authority>/ontologies/<acronym>}.
     *
     * <p>The scheme is forced to {@code http} on purpose. Measured 2026-11-13: {@code
     * http://data.bioontology.org/ontologies/GO} answers the class, while the {@code https} form of the
     * same id answers HTTP 200 with an <em>empty collection</em> — silently, which is the dangerous
     * part. {@code GET /ontologies/GO} advertises {@code "@id":
     * "https://data.bioontology.org/ontologies/GO"}, i.e. the documented id is the one that does not
     * work; the committed {@code bioportal-ontology-GO.json} is that body. It must therefore not be
     * taken from there and not derived from the base URL's scheme.
     */
    private String ontologyId(String acronym) {
        return "http://" + authorityOf(baseUrl) + "/ontologies/" + acronym;
    }

    /** The {@code host[:port]} of the base URL, with any scheme, userinfo, path or query removed. */
    private static String authorityOf(String url) {
        var start = url.indexOf("://");
        var rest = start < 0 ? url : url.substring(start + 3);
        var end = rest.length();
        for (var separator : new char[] {'/', '?', '#'}) {
            var at = rest.indexOf(separator);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        var authority = rest.substring(0, end);
        var at = authority.lastIndexOf('@');
        return at < 0 ? authority : authority.substring(at + 1);
    }

    // ---- safety ------------------------------------------------------------------------------

    /** The method and URL of a request — never a header value, so never the key. */
    private String safeUri(AnnotationProviderHttp.ProviderRequest request) {
        return request.redacted(secretHeaders).toString();
    }

    private static String trimTrailingSlash(String baseUrl) {
        var trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

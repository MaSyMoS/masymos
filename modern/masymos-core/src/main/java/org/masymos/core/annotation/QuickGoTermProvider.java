package org.masymos.core.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The QuickGO term provider for the GO prefix: one batched {@code GET} per chunk of accessions.
 *
 * <p><strong>What is asked.</strong> {@code {base}/services/ontology/go/terms/{acc1},{acc2},...} with
 * every accession as {@code GO:<numeric id>}. The answer is {@code {"numberOfHits":N,"results":[...],
 * "pageInfo":null}}, one result per accession. Batched on purpose: GO terms are decorated in nearly
 * every model, so one request per term would dominate the term stage's request budget.
 *
 * <p><strong>Mapping by index, not by id.</strong> Measured 2026-11-16: the batch answer preserves the
 * requested order ({@code GO:0008150,GO:0043241} answers {@code results[0].id=GO:0008150},
 * {@code results[1].id=GO:0032984}), and a merged id is answered under the <em>successor's</em> id.
 * So {@code results[i]} belongs to the i-th accession of the request, and only the index can pair them:
 * locating the request by the answered id would lose exactly the merged case.
 *
 * <p><strong>Obsolete and merged ids (D11/D12).</strong> The body has no {@code replacedBy},
 * {@code secondaryIds} or {@code replaces} field — it never names the id that was asked. The two
 * signals are therefore:
 *
 * <ul>
 *   <li>{@code results[i].id} equals the requested accession and {@code isObsolete} is true: the term
 *       is obsolete in place ({@code GO:0000003}). It keeps its own text, and the status reports
 *       {@code obsolete=true} with no successor.
 *   <li>{@code results[i].id} differs from the requested accession: the id was merged into
 *       {@code results[i].id} ({@code GO:0043241} answers {@code GO:0032984}). The text is the union
 *       (D12): the cited id's own OBO label ({@code GO_0043241}) first, then the successor's name,
 *       definition and synonyms, in one de-duplicated list and under one cap. The returned key stays
 *       the cited key and the status reports {@code replacedBy} as the successor's curie.
 * </ul>
 *
 * <p>Neither case is ever a negative: both are {@link TermDefinition#resolved} with a
 * {@link TermStatus}, so a merged citation is never cached as a {@code DEFINITIVE} failure.
 *
 * <p><strong>Outcome classification.</strong>
 *
 * <ul>
 *   <li>A 2xx body with a result for the accession: {@code resolved}, with the status above.
 *   <li>A 2xx body whose {@code results[]} does not reach that index: the key is left unanswered and
 *       the next provider in the chain gets a chance at it. Only {@link
 *       IllegalArgumentException} for a key that is not a GO accession is answered definitively; a
 *       missing answer is not evidence that the term does not exist.
 *   <li>A non-2xx status: {@link TermDefinition.FailureClass#TRANSIENT} for every key of the chunk. A
 *       5xx or a rate limit says nothing about the term, and a 404 from a batch endpoint is about the
 *       request, not about one id.
 *   <li>No response, a body that is not JSON, or a transport failure: throws, and the resolver demotes
 *       this provider for the rest of the run.
 * </ul>
 *
 * <p>QuickGO needs no key: this provider sends no {@code Authorization} header and reads none.
 */
public final class QuickGoTermProvider implements AnnotationTermProvider {

    private static final String TERMS_PATH = "/services/ontology/go/terms/";
    private static final String ACCESSION_PREFIX = "GO:";
    private static final String PREFIX = "go";

    /** How many accessions one request carries; an adapter detail, not a contract with QuickGO. */
    static final int CHUNK_SIZE = 50;

    private static final String RESULTS_FIELD = "results";
    private static final String ID_FIELD = "id";
    private static final String OBSOLETE_FIELD = "isObsolete";
    private static final String NAME_FIELD = "name";
    private static final String DEFINITION_FIELD = "definition";
    private static final String TEXT_FIELD = "text";
    private static final String COMMENT_FIELD = "comment";
    private static final String SYNONYMS_FIELD = "synonyms";

    /** The name this provider reports as the source of a {@link TermStatus}. */
    private static final String STATUS_SOURCE = "quickgo";

    private static final String NOT_AN_ACCESSION = "QuickGO: not a GO accession: ";
    private static final String BATCH_FAILED = "QuickGO batch failed: ";
    private static final String MERGED = "QuickGO: merged into ";

    private final String baseUrl;
    private final AnnotationProviderHttp http;
    private final int maxTextLength;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuickGoTermProvider(String baseUrl, AnnotationProviderHttp http, int maxTextLength) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.http = Objects.requireNonNull(http, "http");
        this.maxTextLength = maxTextLength < 1
                ? AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH
                : maxTextLength;
    }

    @Override
    public TermDefinition.Source source() {
        return TermDefinition.Source.QUICKGO;
    }

    /** True exactly for the {@code go} prefix. Pure: no I/O, never throws. */
    @Override
    public boolean supports(TermKey key) {
        return key != null && PREFIX.equals(key.prefix());
    }

    /** The batch URL for the accessions, before any request is sent. Pure. */
    public String batchUrl(Collection<String> accessions) {
        return baseUrl + TERMS_PATH + String.join(",", accessions);
    }

    @Override
    public Map<TermKey, TermDefinition> resolveAll(Collection<TermKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        var answered = new LinkedHashMap<TermKey, TermDefinition>();
        var accepted = new ArrayList<TermKey>();
        for (var key : keys) {
            if (key == null || answered.containsKey(key) || !supports(key)) {
                continue;
            }
            accepted.add(key);
        }
        for (var start = 0; start < accepted.size(); start += CHUNK_SIZE) {
            var chunk = accepted.subList(start, Math.min(start + CHUNK_SIZE, accepted.size()));
            answered.putAll(resolveChunk(chunk));
        }
        return answered;
    }

    /** One request for one chunk: a 2xx body is parsed, any other status is a transient failure. */
    private Map<TermKey, TermDefinition> resolveChunk(List<TermKey> chunk) {
        var answered = new LinkedHashMap<TermKey, TermDefinition>();
        var accessions = new ArrayList<String>(chunk.size());
        var asked = new ArrayList<TermKey>(chunk.size());
        for (var key : chunk) {
            if (isAccession(key.id())) {
                accessions.add(ACCESSION_PREFIX + key.id());
                asked.add(key);
            } else {
                answered.put(key, TermDefinition.unresolved(
                        key, TermDefinition.FailureClass.DEFINITIVE, NOT_AN_ACCESSION + key.id()));
            }
        }
        if (asked.isEmpty()) {
            return answered;
        }
        answered.putAll(askBatch(asked, accessions));
        return answered;
    }

    /** The chunk's request: a 2xx body is parsed, any other status is a transient failure. */
    private Map<TermKey, TermDefinition> askBatch(List<TermKey> chunk, List<String> accessions) {
        var request = AnnotationProviderHttp.ProviderRequest.get(
                batchUrl(accessions), Map.of("Accept", "application/json"));
        var response = http.send(request);
        if (response == null) {
            throw new IllegalStateException(BATCH_FAILED + "no response: " + request);
        }
        if (!response.successful()) {
            var reason = BATCH_FAILED + "HTTP status " + response.status() + ": " + request;
            var failed = new LinkedHashMap<TermKey, TermDefinition>();
            for (var key : chunk) {
                failed.put(key, TermDefinition.unresolved(key, TermDefinition.FailureClass.TRANSIENT, reason));
            }
            return failed;
        }
        return readResults(chunk, accessions, response.body(), request);
    }

    /**
     * The parsed answer: {@code results[i]} belongs to the i-th accession of the request. A result
     * beyond the array is simply not answered, so the next provider gets the key.
     */
    private Map<TermKey, TermDefinition> readResults(
            List<TermKey> chunk,
            List<String> accessions,
            String body,
            AnnotationProviderHttp.ProviderRequest request) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException(BATCH_FAILED + "empty body: " + request);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(BATCH_FAILED + "body is not JSON: "
                    + exception.getClass().getSimpleName() + ": " + request);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException(BATCH_FAILED + "body is not a JSON object: " + request);
        }
        var results = root.get(RESULTS_FIELD);
        var answered = new LinkedHashMap<TermKey, TermDefinition>();
        if (results == null || !results.isArray()) {
            return answered;
        }
        for (var index = 0; index < chunk.size() && index < results.size(); index++) {
            var result = results.get(index);
            if (result == null || !result.isObject()) {
                continue;
            }
            var key = chunk.get(index);
            var answeredId = text(result.get(ID_FIELD));
            if (answeredId == null) {
                continue;
            }
            answered.put(key, definition(key, accessions.get(index), answeredId, result));
        }
        return answered;
    }

    /**
     * One result, with the merge signal decided by id equality: {@code answeredId} equal to the
     * requested accession is the term itself, anything else is the successor it was merged into.
     */
    private TermDefinition definition(TermKey key, String requested, String answeredId, JsonNode result) {
        var name = text(result.get(NAME_FIELD));
        var definitionText = definitionText(result);
        var comment = text(result.get(COMMENT_FIELD));
        var synonyms = synonyms(result.get(SYNONYMS_FIELD));
        var obsolete = isTrue(result.get(OBSOLETE_FIELD));

        if (requested.equals(answeredId)) {
            var values = new LinkedHashSet<String>();
            var seen = new LinkedHashSet<String>();
            addText(values, seen, name);
            addText(values, seen, definitionText);
            addText(values, seen, comment);
            for (var synonym : synonyms) {
                addText(values, seen, synonym);
            }
            var status = obsolete
                    ? new TermStatus(true, null, comment, STATUS_SOURCE)
                    : TermStatus.none();
            return TermDefinition.resolved(key, cap(values), TermDefinition.Source.QUICKGO, status);
        }

        // D12: what the citation says, then what it means. The cited id's own OBO label is the floor,
        // because the body never names it; the successor's published text follows in the same
        // de-duplicated list and under the same cap.
        var values = new LinkedHashSet<String>();
        var seen = new LinkedHashSet<String>();
        addText(values, seen, oboLabel(key));
        addText(values, seen, name);
        addText(values, seen, definitionText);
        for (var synonym : synonyms) {
            addText(values, seen, synonym);
        }
        var reason = comment != null ? comment : MERGED + answeredId;
        return TermDefinition.resolved(
                key, cap(values), TermDefinition.Source.QUICKGO,
                new TermStatus(true, answeredId, reason, STATUS_SOURCE));
    }

    /**
     * The definition text of a result: {@code definition.text} as QuickGO publishes it, or a flat
     * {@code definition} string should a body carry one.
     */
    private static String definitionText(JsonNode result) {
        var definition = result.get(DEFINITION_FIELD);
        if (definition != null && definition.isObject()) {
            return text(definition.get(TEXT_FIELD));
        }
        return text(definition);
    }

    /** The OBO label of a cited key: {@code GO:0043241} becomes {@code GO_0043241}. */
    private static String oboLabel(TermKey key) {
        return "GO_" + key.id();
    }

    /** The names of {@code synonyms[]}, in published order; de-duplication happens on insertion. */
    private static List<String> synonyms(JsonNode synonyms) {
        var names = new ArrayList<String>();
        if (synonyms == null || !synonyms.isArray()) {
            return names;
        }
        for (var synonym : synonyms) {
            var name = synonym != null && synonym.isObject()
                    ? text(synonym.get(NAME_FIELD))
                    : text(synonym);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** Whether a marker is true, as a boolean or as the string spelling of one. */
    private static boolean isTrue(JsonNode marker) {
        if (marker == null || marker.isNull()) {
            return false;
        }
        return marker.isValueNode() && marker.asText().trim().equalsIgnoreCase("true");
    }

    /** The text of a scalar field, or {@code null} when it is absent or not a value. */
    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    /** Whether an id is the numeric part of a GO accession, so {@code GO:} plus it is one. */
    static boolean isAccession(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        for (var index = 0; index < id.length(); index++) {
            var character = id.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    /** Newline-joined under the cap, exactly as {@link Ols4TermProvider} does it. */
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

    /** Collapses whitespace and skips anything already seen, ignoring case; first spelling kept. */
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

    private static String trimTrailingSlash(String baseUrl) {
        var trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

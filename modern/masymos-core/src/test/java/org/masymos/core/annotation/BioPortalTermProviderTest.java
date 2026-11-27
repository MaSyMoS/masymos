package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.masymos.core.annotation.AnnotationProviderHttp.ProviderRequest;
import org.masymos.core.annotation.AnnotationProviderHttp.ProviderResponse;
import org.masymos.core.annotation.TermDefinition.Source;
import org.masymos.core.annotation.TermDefinition.Status;

/**
 * BioPortal adapter tests: no network, an injected transport, and the committed capture {@code
 * bioportal-batch.json} (one real batch answer for the twelve-term sample, 28277 bytes, measured
 * 2026-11-13 against {@code https://data.bioontology.org}) for every success path. Failure shapes are
 * inline literals, because they test the shape of a failure, not a captured body.
 */
class BioPortalTermProviderTest {

    private static final String KEY = "injected-test-key-0123456789";
    private static final String BASE = "https://bioportal.example";
    private static final String FIXTURE = "bioportal-batch.json";

    private static final TermKey GO = new TermKey("go", "0008150");
    private static final TermKey CHEBI = new TermKey("chebi", "15377");
    private static final TermKey FMA = new TermKey("fma", "8445");
    private static final TermKey SBO = new TermKey("sbo", "0000001");
    private static final TermKey KISAO = new TermKey("kisao", "0000019");
    private static final TermKey CHEBI_MRNA = new TermKey("chebi", "16526");

    private static final String GO_IRI = "http://purl.obolibrary.org/obo/GO_0008150";
    private static final String CHEBI_IRI = "http://purl.obolibrary.org/obo/CHEBI_15377";
    private static final String KISAO_IRI = "http://www.biomodels.net/kisao/KISAO#KISAO_0000019";

    private final AnnotationPrefixCatalogue catalogue = AnnotationPrefixCatalogue.standard();

    // ---- 1. chunking ---------------------------------------------------------------------------

    @Test
    void chunksTheInputIntoSizedRequestsAndNeverPerTerm() {
        var transport = new SpyTransport(200, "{}");
        var provider = provider(transport, 2);
        var keys = List.of(GO, CHEBI, FMA, SBO, KISAO);

        provider.resolveAll(keys);

        assertEquals(3, transport.requests.size(), "five keys at maxBatch=2 is three requests");
        assertEquals(List.of(2, 2, 1), transport.iriCounts(), "chunk sizes");
        assertEquals(2, provider.maxBatch(), "the default is 20; the constructor value wins");
        assertTrue(provider.resolveAll(List.of()).isEmpty(), "no keys, no result");
        assertEquals(3, transport.requests.size(), "an empty call sends nothing");
    }

    // ---- 2. the wire format --------------------------------------------------------------------

    @Test
    void theRequestIsAPostToBatchWithTheMediaTypeBodyAndTheTokenHeader() throws Exception {
        var transport = new SpyTransport(200, "{}");
        var provider = provider(transport, 20);

        provider.resolveAll(List.of(GO, CHEBI, KISAO));

        assertEquals(1, transport.requests.size());
        var request = transport.requests.getFirst();
        assertEquals("POST", request.method());
        assertEquals(BASE + "/batch", request.url(), "the endpoint is /batch, not /ontologies/batch");
        assertFalse(request.url().contains("display="), "display belongs in the body: " + request.url());
        assertEquals("apikey token=" + KEY, request.headers().get("Authorization"),
                "the bare 'apikey <key>' form answers 401");

        var body = new ObjectMapper().readTree(request.body());
        assertEquals(List.of(BioPortalTermProvider.CLASS_MEDIA_TYPE), fieldNames(body),
                "the body is keyed by media type");
        var answer = body.get(BioPortalTermProvider.CLASS_MEDIA_TYPE);
        assertEquals("prefLabel,definition,synonym", answer.get("display").asText());

        var collection = answer.get("collection");
        assertEquals(3, collection.size(), "one entry per class");
        assertEquals(List.of(
                GO_IRI + "|http://bioportal.example/ontologies/GO",
                CHEBI_IRI + "|http://bioportal.example/ontologies/CHEBI",
                KISAO_IRI + "|http://bioportal.example/ontologies/KISAO"), collectionEntries(collection),
                "each entry carries its class IRI and its ontology id in http form");
    }

    @Test
    void theOntologyIdIsHttpEvenWhenTheBaseUrlIsHttps() throws Exception {
        var transport = new SpyTransport(200, "{}");

        new BioPortalTermProvider("https://data.bioontology.org", KEY, 20, transport, catalogue,
                AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH).resolveAll(List.of(GO));

        var collection = new ObjectMapper().readTree(transport.requests.getFirst().body())
                .get(BioPortalTermProvider.CLASS_MEDIA_TYPE).get("collection");
        assertEquals("http://data.bioontology.org/ontologies/GO",
                collection.get(0).get("ontology").asText(),
                "the https form answers 200 with an empty collection");
    }

    // ---- 3. keyword text -----------------------------------------------------------------------

    @Test
    void theRealAnswerClaimsClassesByAtIdAndBuildsPrefLabelFirstText() {
        var provider = provider(new SpyTransport(200, fixture(FIXTURE)), 20);

        var answers = provider.resolveAll(List.of(GO, CHEBI_MRNA));

        var go = answers.get(GO);
        assertNotNull(go, "the class is claimed by its @id, not by 'id'");
        assertEquals(Status.RESOLVED, go.status());
        assertEquals(Source.BIOPORTAL, go.source());
        assertEquals("""
                biological_process
                Note that, in addition to forming the root of the biological process ontology, this \
                term is recommended for the annotation of gene products whose biological process is \
                unknown. When this term is used for annotation, it indicates that no information was \
                available about the biological process of the gene product annotated as of the date \
                the annotation was made; the evidence code 'no data' (ND), is used to indicate this.
                A biological process is the execution of a genetically-encoded biological module or \
                program. It consists of all the steps required to achieve the specific biological \
                objective of the module. A biological process is accomplished by a particular set of \
                molecular functions carried out by specific gene products (or macromolecular \
                complexes), often in a highly regulated manner and in a particular temporal sequence.
                physiological process
                biological process
                single organism process
                single-organism process""", go.keywordText(),
                "prefLabel, then every definition, then every synonym");

        var chebi = answers.get(CHEBI_MRNA);
        assertNotNull(chebi);
        assertEquals("carbon dioxide", firstLine(chebi.keywordText()),
                "the fixture's prefLabel comes first");
    }

    @Test
    void theRealKisaoEntryPutsPrefLabelFirstAndCarriesItsSynonymsBeforeOrAroundTheDefinition() {
        var provider = provider(new SpyTransport(200, fixture(FIXTURE)), 20);

        var text = provider.resolveAll(List.of(KISAO)).get(KISAO).keywordText();
        var lines = List.of(text.split("\n"));

        assertEquals("CVODE", lines.get(0), "prefLabel first");
        assertTrue(lines.contains("VODEPK"), "synonym is a top-level array: " + text);
        assertTrue(lines.contains("VODE"));
        assertTrue(lines.contains("code value ordinary differential equation solver"));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("The CVODE is a package written in C")),
                "and the definition is there too: " + text);
        assertEquals(5, lines.size(), "one label, one definition, three synonyms, all distinct");
    }

    @Test
    void theRealSboEntryRoutesItsSynonymStubToAName() {
        // The committed batch carries "Synonym: Kmp " for SBO:0000323 in the definition array, the
        // same stub OLS4 puts in `definition` with definitionProperty rdfs:comment.
        var sbo = new TermKey("sbo", "0000323");

        var text = provider(new SpyTransport(200, fixture(FIXTURE)), 20)
                .resolveAll(List.of(sbo)).get(sbo).keywordText();

        assertEquals("Michaelis constant for product\nKmp", text,
                "the stub is a name, and the stub word itself is not indexed");
    }

    @Test
    void theCapturedFmaTermThatOls4CannotDescribeCarriesItsTextHere() {
        // The committed single-class capture is the BioPortal half of the ANNO-1f pair: the same
        // FMA:305751 that OLS4 answers with its label alone. It is wrapped in the batch envelope here
        // because the adapter is batch-shaped, not because the provider's node shape differs.
        var fma = new TermKey("fma", "305751");
        var batch = "{\"" + BioPortalTermProvider.CLASS_MEDIA_TYPE + "\": ["
                + fixture("bioportal-class-fma-305751.json") + "]}";

        var text = provider(new SpyTransport(200, batch), 20)
                .resolveAll(List.of(fma)).get(fma).keywordText();

        assertTrue(text.startsWith("Anatomical structure\nMaterial anatomical entity which is generated"),
                text);
        assertTrue(text.endsWith("\nBiological structure"), "the synonym is the last line: " + text);
    }

    @Test
    void keywordTextIsCappedAtTheConfiguredMaximum() {
        var provider = new BioPortalTermProvider(BASE, KEY, 20,
                new SpyTransport(200, fixture(FIXTURE)), catalogue, 12);

        var text = provider.resolveAll(List.of(GO)).get(GO).keywordText();

        assertEquals(12, text.length());
        assertEquals("biological_p", text, "a cap cuts inside a line rather than dropping the line");
    }

    @Test
    void aScalarFieldIsAcceptedWhereAListIsExpected() {
        var response = """
                {"http://www.w3.org/2002/07/owl#Class":
                  [{"@id": "%GO%", "prefLabel": "biological_process", "definition": "one definition"}]}
                """.replace("%GO%", GO_IRI);

        var text = provider(new SpyTransport(200, response), 20)
                .resolveAll(List.of(GO)).get(GO).keywordText();

        assertEquals("biological_process\none definition", text);
    }

    @Test
    void anAnswerThatNamesAnIriNobodyAskedForIsSkipped() {
        var response = """
                {"http://www.w3.org/2002/07/owl#Class":
                  [{"@id": "http://purl.obolibrary.org/obo/GO_9999999", "prefLabel": "unasked"}]}
                """;

        assertTrue(provider(new SpyTransport(200, response), 20).resolveAll(List.of(GO)).isEmpty(),
                "nothing was asked about that IRI, so its key travels on");
    }

    // ---- 4. an empty answer is not a failure ---------------------------------------------------

    @Test
    void anEmptyCollectionYieldsNoAnswersAndNoException() {
        var provider = provider(new SpyTransport(200, "{\"http://www.w3.org/2002/07/owl#Class\": []}"), 20);

        assertTrue(provider.resolveAll(List.of(GO, KISAO)).isEmpty(),
                "an empty collection means nothing was learned; the keys go to OLS4");
    }

    @Test
    void aClassAbsentFromTheRealAnswerIsNotClaimed() {
        // KISAO_0000245 is absent from the fixture: BioPortal answers 404 for it, as does OLS4.
        var absent = new TermKey("kisao", "0000245");

        var answers = provider(new SpyTransport(200, fixture(FIXTURE)), 20).resolveAll(List.of(absent));

        assertTrue(answers.isEmpty(), "an unanswered class is not knowledge and not a definitive miss");
    }

    // ---- 5. batch failures throw ---------------------------------------------------------------

    @Test
    void aNon2xxBatchResponseThrows() {
        var provider = provider(new SpyTransport(500, "upstream unavailable"), 20);

        var thrown = assertThrows(IllegalStateException.class, () -> provider.resolveAll(List.of(GO)));

        assertTrue(thrown.getMessage().contains("500"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains(KEY), thrown.getMessage());
    }

    @Test
    void aRateLimitMarksOnlyItsChunkTransientAndDoesNotDemote() {
        var calls = new AtomicInteger();
        AnnotationProviderHttp transport = request -> {
            if (calls.getAndIncrement() == 0) {
                return new ProviderResponse(429, "slow down", "application/json");
            }
            return new ProviderResponse(
                    200,
                    "{\"application/json\":[{\"@id\":\"" + CHEBI_IRI + "\",\"prefLabel\":\"water\"}]}",
                    "application/json");
        };
        var resolver = new AnnotationTermResolver(
                catalogue,
                List.of(provider(transport, 1)),
                new EmptyCache(),
                java.time.Clock.fixed(java.time.Instant.parse("2026-11-21T12:00:00Z"), java.time.ZoneOffset.UTC),
                java.time.Duration.ofDays(30),
                url -> AnnotationFetchResult.failure(url, "the page fallback is not part of this assertion"),
                new AnnotationTextExtractor(),
                AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);

        var result = resolver.resolve(List.of(GO, CHEBI), ResolverMode.PROVIDERS, false);

        assertTrue(result.summary().providersDemoted().isEmpty(),
                "a 429 is a rate limit, not a reason to drop the provider: " + result.summary());
        assertEquals(TermDefinition.FailureClass.TRANSIENT, result.definitions().get(GO).failureClass());
        assertEquals("water", result.definitions().get(CHEBI).keywordText(),
                "the second chunk still resolved");
    }

    @Test
    void aTransportFailureThrows() {
        var provider = new BioPortalTermProvider(BASE, KEY, 20, request -> {
            throw new IllegalStateException("connect timed out");
        }, catalogue, AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);

        assertThrows(IllegalStateException.class, () -> provider.resolveAll(List.of(GO)));
    }

    @Test
    void aBodyThatIsNotJSONThrows() {
        var provider = provider(new SpyTransport(200, "<html>not json</html>"), 20);

        assertThrows(IllegalStateException.class, () -> provider.resolveAll(List.of(GO)));
    }

    @Test
    void aClassAnswerWithoutTextThrows() {
        // A node without any text is the provider answering about a class but saying nothing to index.
        var response = "{\"http://www.w3.org/2002/07/owl#Class\": [{\"@id\": \"" + GO_IRI + "\"}]}";

        assertThrows(IllegalStateException.class,
                () -> provider(new SpyTransport(200, response), 20).resolveAll(List.of(GO)));
    }

    // ---- 6. capability -------------------------------------------------------------------------

    @Test
    void supportsExactlyThePrefixesWithABioPortalOntology() {
        var provider = provider(new SpyTransport(200, "{}"), 20);

        assertTrue(provider.supports(GO));
        assertTrue(provider.supports(CHEBI));
        assertTrue(provider.supports(FMA));
        assertTrue(provider.supports(SBO));
        assertTrue(provider.supports(KISAO), "BioPortal serves KISAO");
        assertFalse(provider.supports(new TermKey("kegg", "hsa00010")), "not ontological");
        assertFalse(provider.supports(null));
        assertEquals(Source.BIOPORTAL, provider.source());
    }

    @Test
    void keysItDoesNotServeAreNeverAskedAbout() {
        var transport = new SpyTransport(200, "{}");
        var provider = provider(transport, 20);

        var answered = provider.resolveAll(List.of(new TermKey("kegg", "hsa00010"), GO));

        assertFalse(answered.containsKey(new TermKey("kegg", "hsa00010")));
        assertEquals(1, transport.iriCounts().getFirst(), "only the served key is in the body");
    }

    // ---- 7. the key in no message --------------------------------------------------------------

    @Test
    void theKeyAppearsInNoExceptionMessage() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> provider(new SpyTransport(500, "boom"), 20).resolveAll(List.of(GO)));
        var notJson = assertThrows(IllegalStateException.class,
                () -> provider(new SpyTransport(200, "nope"), 20).resolveAll(List.of(GO)));
        var transportThrow = assertThrows(IllegalStateException.class,
                () -> new BioPortalTermProvider(BASE, KEY, 20, request -> {
                    throw new IllegalStateException("read timed out");
                }, catalogue, AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH).resolveAll(List.of(GO)));
        var rendered = new ProviderRequest("POST", BASE + BioPortalTermProvider.BATCH_PATH,
                Map.of("Authorization", "apikey token=" + KEY, "Content-Type", "application/json"), "{}")
                .redacted(Set.of("Authorization"));

        for (var exception : List.of(thrown, notJson, transportThrow)) {
            var message = String.valueOf(exception.getMessage());
            assertFalse(message.contains(KEY), message);
        }
        assertFalse(rendered.toString().contains(KEY));
        assertFalse(rendered.redacted(Set.of("Authorization")).headers().get("Authorization").contains(KEY));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private BioPortalTermProvider provider(AnnotationProviderHttp transport, int maxBatch) {
        return new BioPortalTermProvider(BASE, KEY, maxBatch, transport, catalogue,
                AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
    }

    private static List<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        var names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> collectionEntries(com.fasterxml.jackson.databind.JsonNode collection) {
        var entries = new ArrayList<String>();
        collection.forEach(entry -> entries.add(entry.get("class").asText() + "|" + entry.get("ontology").asText()));
        return entries;
    }

    private static String firstLine(String text) {
        var newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** A captured body from the test classpath, byte for byte. */
    private static String fixture(String name) {
        var resource = "annotation/providers/" + name;
        try (InputStream stream = BioPortalTermProviderTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** A cache that holds nothing: every key is asked of the providers. */
    private static final class EmptyCache implements AnnotationTermCache {

        @Override
        public Map<TermKey, CachedTerm> findAll(java.util.Collection<TermKey> keys) {
            return Map.of();
        }

        @Override
        public void saveAll(java.util.Collection<CachedTerm> terms) {
            // Nothing is remembered between runs here.
        }
    }

    /** A transport that records every request and answers with a fixed status and body. */
    private static final class SpyTransport implements AnnotationProviderHttp {

        private final int status;
        private final String body;
        private final List<ProviderRequest> requests = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        SpyTransport(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public ProviderResponse send(ProviderRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            return new ProviderResponse(status, body, "application/json");
        }

        /** How many class IRIs each request's body asked for, in request order. */
        List<Integer> iriCounts() {
            var counts = new ArrayList<Integer>();
            var mapper = new ObjectMapper();
            for (var request : requests) {
                var total = 0;
                try {
                    var tree = mapper.readTree(request.body());
                    for (var mediaType : iterable(tree.fields())) {
                        total += mediaType.getValue().get("collection").size();
                    }
                } catch (Exception exception) {
                    throw new AssertionError("request body is not JSON: " + request.body(), exception);
                }
                counts.add(total);
            }
            return counts;
        }

        private static <T> List<T> iterable(java.util.Iterator<T> iterator) {
            var list = new ArrayList<T>();
            iterator.forEachRemaining(list::add);
            return list;
        }
    }
}

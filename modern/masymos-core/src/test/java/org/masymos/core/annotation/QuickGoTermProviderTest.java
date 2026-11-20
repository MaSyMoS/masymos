package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.masymos.core.annotation.AnnotationProviderHttp.ProviderRequest;
import org.masymos.core.annotation.AnnotationProviderHttp.ProviderResponse;
import org.masymos.core.annotation.TermDefinition.FailureClass;
import org.masymos.core.annotation.TermDefinition.Source;
import org.masymos.core.annotation.TermDefinition.Status;

/**
 * QuickGO adapter tests: the batched {@code GET}, index mapping, and the two lifecycle signals the body
 * carries without naming them — merge (answered id differs) and obsolescence in place. No network, an
 * injected transport, and the bodies captured on 2026-11-16 under {@code annotation/providers/}.
 *
 * <p>Captures used, one per accession: {@code quickgo-term-go-0008150.json} (two definitions, four
 * synonyms), {@code quickgo-term-go-0032984.json} (the successor), {@code quickgo-term-go-0043241.json}
 * (answers under the successor's id: the merge signal), {@code quickgo-term-go-0000003.json} (obsolete
 * in place, {@code OBSOLETE.} definition, an obsolescence comment), and {@code
 * quickgo-term-go-0051019.json}. Each capture is one single-accession answer, so the stub stitches
 * several captures into a batch body by splicing their {@code results[0]} in request order — the
 * fixture bytes stay exactly the captured ones.
 */
class QuickGoTermProviderTest {

    private static final String BASE = "https://quickgo.example";
    private static final String TERMS = BASE + "/services/ontology/go/terms/";

    private static final TermKey GO_ROOT = new TermKey("go", "0008150");
    private static final TermKey GO_MERGED = new TermKey("go", "0043241");
    private static final TermKey GO_OBSOLETE = new TermKey("go", "0000003");
    private static final TermKey GO_KINASE_BINDING = new TermKey("go", "0051019");

    private static final String ROOT_BODY = fixture("quickgo-term-go-0008150.json");
    private static final String SUCCESSOR_BODY = fixture("quickgo-term-go-0032984.json");
    private static final String MERGED_BODY = fixture("quickgo-term-go-0043241.json");
    private static final String OBSOLETE_BODY = fixture("quickgo-term-go-0000003.json");
    private static final String KINASE_BINDING_BODY = fixture("quickgo-term-go-0051019.json");

    private static final String SUCCESSOR_DEFINITION =
            "The disaggregation of a protein-containing macromolecular complex into its constituent components.";
    private static final String ROOT_DEFINITION = "A biological process is the execution of a "
            + "genetically-encoded biological module or program. It consists of all the steps required to achieve "
            + "the specific biological objective of the module. A biological process is accomplished by a "
            + "particular set of molecular functions carried out by specific gene products (or macromolecular "
            + "complexes), often in a highly regulated manner and in a particular temporal sequence.";
    private static final String ROOT_NOTE_PREFIX = "Note that, in addition to forming the root";
    private static final String OBSOLETE_DEFINITION_PREFIX = "OBSOLETE. The production of new individuals";

    @Test
    void aMergedIdYieldsTheSuccessorsTextAndNamesTheSuccessor() {
        var transport = captureTransport(MERGED_BODY);

        var definition = resolve(GO_MERGED, transport);

        assertEquals(List.of(TERMS + "GO:0043241"), transport.urls(), "one request for one accession");
        assertEquals(Status.RESOLVED, definition.status(), "a merge is metadata, never a negative");
        assertNull(definition.failureClass());
        assertEquals(Source.QUICKGO, definition.source());
        assertEquals(GO_MERGED, definition.key(), "the cited key is never rewritten (D11)");

        var lines = lines(definition.keywordText());
        assertEquals("GO_0043241", lines.get(0), "the cited id's own label comes first:\n" + lines);
        assertTrue(lines.contains(SUCCESSOR_DEFINITION), "then the successor's definition:\n" + lines);
        assertTrue(lines.contains("protein-containing complex disassembly"),
                "and its name:\n" + lines);
        assertTrue(lines.contains("protein complex disassembly"), "and its synonyms:\n" + lines);
        assertEquals(lines.size(), lines.stream().map(line -> line.toLowerCase()).distinct().count(),
                "one de-duplicated list:\n" + lines);

        assertTrue(definition.termStatus().obsolete());
        assertEquals("GO:0032984", definition.termStatus().replacedBy());
        assertEquals("quickgo", definition.termStatus().source());
    }

    @Test
    void anObsoleteIdKeepsItsOwnTextAndNamesNoSuccessor() {
        var definition = resolve(GO_OBSOLETE, captureTransport(OBSOLETE_BODY));

        assertEquals(Status.RESOLVED, definition.status(), "an obsolete id is never a negative");
        var lines = lines(definition.keywordText());
        assertEquals("obsolete reproduction", lines.get(0));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(OBSOLETE_DEFINITION_PREFIX)),
                "its own OBSOLETE. definition is preserved:\n" + lines);
        assertTrue(lines.contains("reproductive physiological process"), "and its own synonym:\n" + lines);

        assertTrue(definition.termStatus().obsolete());
        assertNull(definition.termStatus().replacedBy(), "nothing replaced it");
        assertEquals("The reason for obsoletion is that this term is equivalent to reproductive process.",
                definition.termStatus().reason());
    }

    @Test
    void aMeaningfulTermCarriesBothDefinitionsAndTheEmptyStatus() {
        var definition = resolve(GO_ROOT, captureTransport(ROOT_BODY));

        var lines = lines(definition.keywordText());
        assertEquals("biological_process", lines.get(0));
        assertTrue(lines.contains(ROOT_DEFINITION), "definition.text is indexed:\n" + lines);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(ROOT_NOTE_PREFIX)),
                "the comment is the second definition:\n" + lines);
        assertTrue(lines.contains("physiological process"), "and the synonyms follow:\n" + lines);
        assertEquals(TermStatus.none(), definition.termStatus());
    }

    @Test
    void aSecondOrdinaryTermResolvesFromItsOwnBatchSlot() {
        var transport = captureTransport(Map.of(
                "GO:0008150", ROOT_BODY, "GO:0051019", KINASE_BINDING_BODY));

        var definitions = provider(transport).resolveAll(List.of(GO_ROOT, GO_KINASE_BINDING));

        assertEquals(2, definitions.size(), "one definition per accepted key");
        assertTrue(lines(definitions.get(GO_ROOT).keywordText()).contains(ROOT_DEFINITION));
        var lines = lines(definitions.get(GO_KINASE_BINDING).keywordText());
        assertEquals("mitogen-activated protein kinase binding", lines.get(0));
        assertTrue(lines.contains("Binding to a mitogen-activated protein kinase."));
        assertTrue(lines.contains("MAPK binding"));
        assertFalse(definitions.get(GO_KINASE_BINDING).termStatus().obsolete());
    }

    @Test
    void oneRequestCarriesTheWholeBatchAndResultsAreMappedByIndex() {
        var transport = captureTransport(Map.of(
                "GO:0008150", ROOT_BODY, "GO:0043241", MERGED_BODY));

        var definitions = provider(transport).resolveAll(List.of(GO_ROOT, GO_MERGED));

        assertEquals(1, transport.urls().size(), "one request for the batch: " + transport.urls());
        var url = transport.urls().get(0);
        assertTrue(url.startsWith(TERMS), url);
        assertTrue(url.contains("GO:0008150,GO:0043241"), "both accessions in request order: " + url);

        assertEquals("biological_process", lines(definitions.get(GO_ROOT).keywordText()).get(0),
                "results[0] belongs to GO:0008150");
        assertEquals("GO_0043241", lines(definitions.get(GO_MERGED).keywordText()).get(0),
                "results[1] belongs to GO:0043241 although it answered GO:0032984");
        assertEquals("GO:0032984", definitions.get(GO_MERGED).termStatus().replacedBy());
        assertEquals(TermStatus.none(), definitions.get(GO_ROOT).termStatus());
    }

    @Test
    void aNonSuccessStatusIsTransientForEveryKeyOfTheChunk() {
        var transport = new AnnotationProviderHttp() {
            @Override
            public ProviderResponse send(ProviderRequest request) {
                return new ProviderResponse(503, "upstream busy", "text/plain");
            }
        };

        var definitions = provider(transport).resolveAll(List.of(GO_ROOT, GO_MERGED));

        assertEquals(2, definitions.size());
        for (var key : List.of(GO_ROOT, GO_MERGED)) {
            var definition = definitions.get(key);
            assertEquals(Status.UNRESOLVED, definition.status());
            assertEquals(FailureClass.TRANSIENT, definition.failureClass(), "retried next run: " + key);
            assertTrue(definition.failureReason().contains("503"), definition.failureReason());
        }
    }

    @Test
    void aTransportFailureThrowsSoTheResolverDemotesThisProvider() {
        var provider = provider(request -> {
            throw new IllegalStateException("read timed out");
        });

        assertThrows(IllegalStateException.class, () -> provider.resolveAll(List.of(GO_ROOT)));
    }

    @Test
    void aBatchThatDoesNotReachAnIndexLeavesThatKeyForTheNextProvider() {
        var transport = new AnnotationProviderHttp() {
            @Override
            public ProviderResponse send(ProviderRequest request) {
                return new ProviderResponse(200, ROOT_BODY, "application/json");
            }
        };

        var definitions = provider(transport).resolveAll(List.of(GO_ROOT, GO_KINASE_BINDING));

        assertNotNull(definitions.get(GO_ROOT));
        assertNull(definitions.get(GO_KINASE_BINDING),
                "no answer is not evidence that the term does not exist");
    }

    @Test
    void supportsIsTrueOnlyForTheGoPrefix() {
        var provider = provider(request -> new ProviderResponse(500, "", "text/plain"));

        assertTrue(provider.supports(new TermKey("go", "0008150")));
        assertFalse(provider.supports(new TermKey("sbo", "0000323")));
        assertFalse(provider.supports(new TermKey("chebi", "1")));
        assertFalse(provider.supports(null));
    }

    @Test
    void resolveAllToleratesAnEmptyBatchAndIgnoresUnsupportedKeys() {
        var provider = provider(request -> {
            throw new IllegalStateException("no request expected");
        });

        assertEquals(Map.of(), provider.resolveAll(List.of()));
        assertEquals(Map.of(), provider.resolveAll(null));
        assertEquals(Map.of(), provider.resolveAll(List.of(new TermKey("sbo", "0000323"))));
    }

    @Test
    void theCapIsAppliedAndDefaultsWhenTheArgumentIsUnusable() {
        var capped = new QuickGoTermProvider(BASE, captureTransport(ROOT_BODY), 40)
                .resolveAll(List.of(GO_ROOT)).get(GO_ROOT);
        assertTrue(capped.keywordText().length() <= 40, "the cap is a bound, never exceeded");
        assertEquals("biological_process", capped.keywordText(),
                "the definition would cross the cap, so only the label is kept - no partial line");
        assertEquals(Status.RESOLVED, capped.status());

        var defaulted = new QuickGoTermProvider(BASE, captureTransport(ROOT_BODY), -1)
                .resolveAll(List.of(GO_ROOT)).get(GO_ROOT);
        assertTrue(defaulted.keywordText().length() < AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
        assertTrue(lines(defaulted.keywordText()).stream()
                        .anyMatch(line -> line.startsWith(ROOT_NOTE_PREFIX)),
                "an unusable cap falls back to the default, so nothing is truncated away");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private QuickGoTermProvider provider(AnnotationProviderHttp transport) {
        return new QuickGoTermProvider(BASE, transport, AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
    }

    private TermDefinition resolve(TermKey key, AnnotationProviderHttp transport) {
        var definition = provider(transport).resolveAll(List.of(key)).get(key);
        assertNotNull(definition, "no definition for " + key);
        return definition;
    }

    /** One capture for one accession: the stub answers every requested accession from it. */
    private static CaptureTransport captureTransport(String body) {
        return new CaptureTransport(body);
    }

    private static CaptureTransport captureTransport(Map<String, String> bodiesByAccession) {
        return new CaptureTransport(bodiesByAccession);
    }

    private static List<String> lines(String text) {
        return Arrays.asList(text.split("\n"));
    }

    /** A captured body from the test classpath, byte for byte. */
    private static String fixture(String name) {
        var resource = "annotation/providers/" + name;
        try (InputStream stream = QuickGoTermProviderTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * A transport over the single-accession captures: it reads the requested accession list off the URL
     * and splices each capture's {@code results[0]} into one batch body, in request order, so the
     * provider's index mapping is what the test measures. Any other URL throws, and every request is
     * recorded, because the request economy is part of the contract.
     */
    private static final class CaptureTransport implements AnnotationProviderHttp {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private final Map<String, String> bodies;
        private final String defaultBody;
        private final List<ProviderRequest> requests = new ArrayList<>();

        CaptureTransport(String defaultBody) {
            this(Map.of(), defaultBody);
        }

        CaptureTransport(Map<String, String> bodiesByAccession) {
            this(bodiesByAccession, null);
        }

        private CaptureTransport(Map<String, String> bodiesByAccession, String defaultBody) {
            this.bodies = new LinkedHashMap<>(bodiesByAccession);
            this.defaultBody = defaultBody;
        }

        @Override
        public ProviderResponse send(ProviderRequest request) {
            requests.add(request);
            if (!request.url().startsWith(TERMS)) {
                throw new IllegalStateException("unexpected URL: " + request);
            }
            var results = new StringBuilder("\"results\":[");
            var accessions = request.url().substring(TERMS.length()).split(",");
            for (var index = 0; index < accessions.length; index++) {
                var body = bodies.containsKey(accessions[index])
                        ? bodies.get(accessions[index])
                        : defaultBody;
                if (body == null) {
                    throw new IllegalStateException("no capture for " + accessions[index] + ": " + request);
                }
                if (index > 0) {
                    results.append(',');
                }
                results.append(resultOf(body));
            }
            results.append(']');
            var batch = "{\"numberOfHits\":" + accessions.length + "," + results + ",\"pageInfo\":null}";
            return new ProviderResponse(200, batch, "application/json");
        }

        private static String resultOf(String body) {
            try {
                return MAPPER.readTree(body).get("results").get(0).toString();
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("capture is not a QuickGO answer", exception);
            }
        }

        List<String> urls() {
            return requests.stream().map(ProviderRequest::url).toList();
        }
    }
}

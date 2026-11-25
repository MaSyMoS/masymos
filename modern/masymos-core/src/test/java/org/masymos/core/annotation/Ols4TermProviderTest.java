package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.annotation.AnnotationProviderHttp.ProviderRequest;
import org.masymos.core.annotation.AnnotationProviderHttp.ProviderResponse;
import org.masymos.core.annotation.TermDefinition.FailureClass;
import org.masymos.core.annotation.TermDefinition.Source;
import org.masymos.core.annotation.TermDefinition.Status;

/**
 * OLS4 adapter tests for the class-then-property flow: ask for the class, fall through to the property on
 * a 404, and let the second 404 be the definitive negative. No network, an injected transport, and the
 * real bodies captured on 2026-11-13/14 by {@code scripts/capture-providers.ps1} under {@code
 * annotation/providers/}.
 *
 * <p><strong>Why there is no search any more.</strong> The previous flow typed an IRI from {@code
 * GET /search?q=<iri>&exact=true&queryFields=iri}. That index is not an exact oracle, measured
 * 2026-11-14: {@code GO_0008150} returns 124 hits, because the same IRI appears in imported ontologies,
 * and {@code GO_0043241} returns 0 although {@code /v2/ontologies/go/classes/<iri>} answers 200 for it.
 * A real entity was therefore turned into "no such entity". The two captures {@code
 * ols4-search-go-0008150.json} and {@code ols4-search-go-0043241.json} stay in the tree as the evidence
 * for that sentence; no test here uses them, because no search is made.
 *
 * <p>Captures used:
 *
 * <ul>
 *   <li>{@code ols4-v2-classes-<prefix>-<id>.json}: the eleven class bodies that answer 200. {@code
 *       ols4-v2-classes-go-0008150.json} carries two definitions under two different keys ({@code
 *       definition} and the {@code IAO_0000115} object) and four synonyms; {@code
 *       ols4-v2-classes-fma-62387.json} is label-only.
 *   <li>{@code ols4-v2-classes-kisao-0000245.json} (a 404 body) plus {@code
 *       ols4-v2-properties-kisao-0000245.json} (the 200 property body): the property fallback. {@code
 *       ols4-v2-properties-kisao-0000019.json} (a 404 body) is the reverse direction, kept in the tree.
 * </ul>
 *
 * <p>Only the failure shapes (status codes, transport failure, non-JSON body) stay inline, because what
 * they test is the shape of a failure, not the body of a term.
 *
 * <p>OLS4 takes no key, so "secret" here means: nothing of the request or the response body may leak
 * into a message by accident - neither the rendered URL nor a header value.
 */
class Ols4TermProviderTest {

    private static final String BASE = "https://ols4.example";

    private static final TermKey GO = new TermKey("go", "0008150");
    private static final TermKey GO_NOT_IN_SEARCH = new TermKey("go", "0043241");
    private static final TermKey KISAO = new TermKey("kisao", "0000019");
    private static final TermKey KISAO_PROPERTY = new TermKey("kisao", "0000245");
    private static final TermKey FMA = new TermKey("fma", "62387");
    private static final TermKey KEGG = new TermKey("kegg", "hsa00010");

    /**
     * SBO publishes synonyms in {@code rdfs:comment} as the stub {@code "Synonym: Kmp"} and, in
     * other terms, puts a genuine definition in the same property (measured 2026-11-16 on the two
     * captures below). The two rows are therefore a pair, not one rule for the property.
     */
    private static final TermKey SBO_SYNONYM = new TermKey("sbo", "0000323");
    private static final TermKey SBO_DEFINITION = new TermKey("sbo", "0000176");
    private static final String SBO_SYNONYM_RESPONSE = fixture("ols4-v2-classes-sbo-0000323.json");
    private static final String SBO_DEFINITION_RESPONSE = fixture("ols4-v2-classes-sbo-0000176.json");
    private static final String SBO_SYNONYM_CLASS =
            "/v2/ontologies/sbo/classes/http%253A%252F%252Fbiomodels.net%252FSBO%252FSBO_0000323";
    private static final String SBO_DEFINITION_CLASS =
            "/v2/ontologies/sbo/classes/http%253A%252F%252Fbiomodels.net%252FSBO%252FSBO_0000176";

    private static final String GO_IRI = "http://purl.obolibrary.org/obo/GO_0008150";
    private static final String GO_NOT_IN_SEARCH_IRI = "http://purl.obolibrary.org/obo/GO_0043241";

    private static final String GO_IRI_TWICE = "http%253A%252F%252Fpurl.obolibrary.org%252Fobo%252FGO_0008150";
    private static final String GO_NOT_IN_SEARCH_IRI_TWICE =
            "http%253A%252F%252Fpurl.obolibrary.org%252Fobo%252FGO_0043241";

    private static final String KISAO_IRI_TWICE =
            "http%253A%252F%252Fwww.biomodels.net%252Fkisao%252FKISAO%2523KISAO_0000019";

    private static final String KISAO_PROPERTY_IRI = "http://www.biomodels.net/kisao/KISAO#KISAO_0000245";
    private static final String KISAO_PROPERTY_IRI_TWICE =
            "http%253A%252F%252Fwww.biomodels.net%252Fkisao%252FKISAO%2523KISAO_0000245";

    private static final String GO_CLASS = "/v2/ontologies/go/classes/" + GO_IRI_TWICE;
    private static final String GO_PROPERTY = "/v2/ontologies/go/properties/" + GO_IRI_TWICE;
    private static final String KISAO_CLASS = "/v2/ontologies/kisao/classes/" + KISAO_IRI_TWICE;
    private static final String KISAO_PROPERTY_CLASS = "/v2/ontologies/kisao/classes/" + KISAO_PROPERTY_IRI_TWICE;
    private static final String KISAO_PROPERTY_PROPERTIES =
            "/v2/ontologies/kisao/properties/" + KISAO_PROPERTY_IRI_TWICE;

    /** A header value that must never be rendered; OLS4 sends none, this only tests the rendering. */
    private static final String SECRET = "must-not-appear-0123456789";

    /** The captured 404 body: identical for every entity OLS4 has no class or property for. */
    private static final String NOT_FOUND_BODY = fixture("ols4-v2-classes-kisao-0000245.json");

    /** The captured class bodies: GO with two definitions and four synonyms, FMA label-only. */
    private static final String GO_RESPONSE = fixture("ols4-v2-classes-go-0008150.json");
    private static final String GO_NOT_IN_SEARCH_RESPONSE = fixture("ols4-v2-classes-go-0043241.json");
    private static final String KISAO_RESPONSE = fixture("ols4-v2-classes-kisao-0000019.json");
    private static final String FMA_RESPONSE = fixture("ols4-v2-classes-fma-62387.json");

    /** The captured property body for {@code KISAO_0000245}, {@code has characteristic}. */
    private static final String KISAO_PROPERTY_RESPONSE = fixture("ols4-v2-properties-kisao-0000245.json");

    /** The captures for the merged/obsolete cases, all measured 2026-11-16 against live OLS4 v2. */
    private static final String GO_MERGED_RESPONSE = fixture("ols4-v2-classes-go-0043241.json");
    private static final String GO_SUCCESSOR_RESPONSE = fixture("ols4-v2-classes-go-0032984.json");
    private static final String CHEBI_MERGED_RESPONSE = fixture("ols4-v2-classes-chebi-1.json");
    private static final String CHEBI_SUCCESSOR_RESPONSE = fixture("ols4-v2-classes-chebi-18357.json");
    private static final String GO_OBSOLETE_RESPONSE = fixture("ols4-v2-classes-go-0000005.json");

    private static final String GO_SUCCESSOR_IRI_TWICE =
            "http%253A%252F%252Fpurl.obolibrary.org%252Fobo%252FGO_0032984";
    private static final String GO_SUCCESSOR_CLASS = "/v2/ontologies/go/classes/" + GO_SUCCESSOR_IRI_TWICE;
    private static final String GO_MERGED_CLASS = "/v2/ontologies/go/classes/" + GO_NOT_IN_SEARCH_IRI_TWICE;

    private static final TermKey CHEBI_1 = new TermKey("chebi", "1");
    private static final String CHEBI_1_IRI_TWICE =
            "http%253A%252F%252Fpurl.obolibrary.org%252Fobo%252FCHEBI_1";
    private static final String CHEBI_18357_IRI_TWICE =
            "http%253A%252F%252Fpurl.obolibrary.org%252Fobo%252FCHEBI_18357";
    private static final String CHEBI_1_CLASS = "/v2/ontologies/chebi/classes/" + CHEBI_1_IRI_TWICE;
    private static final String CHEBI_18357_CLASS = "/v2/ontologies/chebi/classes/" + CHEBI_18357_IRI_TWICE;

    private static final TermKey GO_OBSOLETE = new TermKey("go", "0000005");
    private static final String GO_OBSOLETE_IRI_TWICE =
            "http%253A%252F%252Fpurl.obolibrary.org%252Fobo%252FGO_0000005";
    private static final String GO_OBSOLETE_CLASS = "/v2/ontologies/go/classes/" + GO_OBSOLETE_IRI_TWICE;

    /**
     * {@code GO:0000003} is obsolete <em>and</em> merged into {@code GO:0022414} <em>and</em> still
     * publishes its own {@code OBSOLETE.} definition — the case D12 exists for.
     */
    private static final TermKey GO_OBSOLETE_MERGED = new TermKey("go", "0000003");
    private static final String GO_OBSOLETE_MERGED_RESPONSE = fixture("ols4-v2-classes-go-0000003.json");
    private static final String GO_OBSOLETE_MERGED_IRI_TWICE =
            "http%253A%252F%252Fpurl.obolibrary.org%252Fobo%252FGO_0000003";
    private static final String GO_OBSOLETE_MERGED_CLASS =
            "/v2/ontologies/go/classes/" + GO_OBSOLETE_MERGED_IRI_TWICE;
    private static final String GO_REPRODUCTIVE_PROCESS_RESPONSE = fixture("ols4-v2-classes-go-0022414.json");
    private static final String GO_REPRODUCTIVE_PROCESS_CLASS =
            "/v2/ontologies/go/classes/http%253A%252F%252Fpurl.obolibrary.org%252Fobo%252FGO_0022414";
    /** The cited term's own (obsolete) definition: the words a searcher of the citation will use. */
    private static final String GO_OBSOLETE_DEFINITION =
            "OBSOLETE. The production of new individuals that contain some portion of genetic material"
                    + " inherited from one or more parent organisms.";
    /** The successor's definition, which the cited id also means. */
    private static final String GO_REPRODUCTIVE_DEFINITION =
            "A biological process that directly contributes to the process of producing new individuals by"
                    + " one or two organisms. The new individuals inherit some proportion of their genetic"
                    + " material from the parent or parents.";

    private static final String GO_SUCCESSOR_DEFINITION =
            "The disaggregation of a protein-containing macromolecular complex into its constituent components.";
    /**
     * The successor's definition as the provider indexes it: measured 2026-11-16, the ChEBI body
     * carries {@code <i>R</i>} markup, and neither provider strips markup (the text extractor does that
     * for pages only). The sentence itself is "The <i>R</i>-enantiomer of noradrenaline."
     */
    private static final String CHEBI_SUCCESSOR_DEFINITION = "The <i>R</i>-enantiomer of noradrenaline.";

    /** The captured 404 body of the property path, the reverse direction of the fallback. */
    private static final String KISAO_PROPERTY_NOT_FOUND = fixture("ols4-v2-properties-kisao-0000019.json");

    private final AnnotationPrefixCatalogue catalogue = AnnotationPrefixCatalogue.standard();

    // ---- 1. capability -------------------------------------------------------------------------

    @Test
    void supportsExactlyThePrefixesWithAnOls4Ontology() {
        var provider = provider(classAnswer(GO_CLASS, GO_RESPONSE));

        for (var key : List.of(GO, KISAO, KISAO_PROPERTY, FMA, new TermKey("chebi", "16526"),
                new TermKey("sbo", "0000323"))) {
            assertTrue(provider.supports(key), "the catalogue maps " + key.prefix() + " to OLS4");
        }
        assertFalse(provider.supports(KEGG), "not ontological");
        assertFalse(provider.supports(null));
        assertEquals(Source.OLS4, provider.source());
    }

    @Test
    void aPrefixItDoesNotServeIsNeverAskedAbout() {
        var transport = classAnswer(GO_CLASS, GO_RESPONSE);
        var provider = provider(transport);

        assertTrue(provider.resolveAll(List.of(KEGG)).isEmpty());
        assertTrue(transport.requests.isEmpty(), "no request for a prefix without an OLS4 ontology");
        assertTrue(provider.resolveAll(List.of()).isEmpty(), "no keys, no result");
        assertTrue(provider.resolveAll(null).isEmpty(), "null keys, no result");
        assertTrue(transport.requests.isEmpty());
    }

    // ---- 2. the requests -----------------------------------------------------------------------

    @Test
    void aClassIsOneGetAndTheClassCallItself() {
        var transport = classAnswer(GO_CLASS, GO_RESPONSE);

        var answered = provider(transport).resolveAll(List.of(GO));

        assertEquals(List.of(BASE + GO_CLASS), transport.urls(),
                "a class is exactly one request: the class call, no search and no property probe");
        assertEquals(Status.RESOLVED, answered.get(GO).status());
        for (var request : transport.requests) {
            assertEquals("GET", request.method());
            assertEquals("application/json", request.headers().get("Accept"));
            assertEquals("GET " + request.url(), request.toString(), "the rendering is method and URL only");
        }
    }

    @Test
    void theClassUrlUsesTheCatalogueOntologyAndDoubleEncodesTheIri() {
        var provider = provider(classAnswer(GO_CLASS, GO_RESPONSE));

        assertEquals(BASE + GO_CLASS, provider.detailUrl("go", GO_IRI, false));
        assertEquals(BASE + KISAO_PROPERTY_PROPERTIES,
                provider.detailUrl("kisao", KISAO_PROPERTY_IRI, true));

        var url = provider.detailUrl("go", GO_IRI, false);
        assertTrue(url.startsWith(BASE + "/v2/ontologies/go/classes/"), url);
        assertFalse(url.contains("http://"), "the single-encoded form must not survive: " + url);
        var afterClasses = url.substring(url.indexOf("/classes/") + "/classes/".length());
        assertFalse(afterClasses.contains("/"), "the IRI plus its slashes stays inside one segment: " + url);
        assertFalse(url.contains("?"), "the detail read takes no query parameters: " + url);
        assertFalse(url.contains("search"), "there is no search step any more: " + url);
    }

    @Test
    void theKisaoPropertyCostsExactlyTwoRequestsAClassFourOhFourThenTheProperty() {
        var transport = classAnswer(Map.of(KISAO_PROPERTY_CLASS, 404, KISAO_PROPERTY_PROPERTIES, 200),
                Map.of(KISAO_PROPERTY_CLASS, NOT_FOUND_BODY, KISAO_PROPERTY_PROPERTIES, KISAO_PROPERTY_RESPONSE));

        var answered = provider(transport).resolveAll(List.of(KISAO_PROPERTY));

        assertEquals(List.of(BASE + KISAO_PROPERTY_CLASS, BASE + KISAO_PROPERTY_PROPERTIES), transport.urls(),
                "exactly two requests: the class call answers 404, the property call answers 200");
        var definition = answered.get(KISAO_PROPERTY);
        assertEquals(Status.RESOLVED, definition.status(),
                "KISAO_0000245 is an objectProperty: the fallback resolves it, it is not a 404");
        assertEquals(Source.OLS4, definition.source());
        assertEquals("has characteristic\n'has characteristic' links algorithms to the characteristics they "
                + "possess.", definition.keywordText(), "label, then the property's one skos:definition");
    }

    @Test
    void aKisaoClassCostsOneRequestAndNeverProbesThePropertyPath() {
        var transport = classAnswer(KISAO_CLASS, KISAO_RESPONSE);

        provider(transport).resolveAll(List.of(KISAO));

        assertEquals(List.of(BASE + KISAO_CLASS), transport.urls(),
                "the class 200 ends the lookup; no property request");
    }

    @Test
    void aGoAndAKisaoPropertyInOneRunAreThreeRequestsAndTwoAnswers() {
        var transport = classAnswer(
                Map.of(GO_CLASS, 200, KISAO_PROPERTY_CLASS, 404, KISAO_PROPERTY_PROPERTIES, 200),
                Map.of(GO_CLASS, GO_RESPONSE, KISAO_PROPERTY_CLASS, NOT_FOUND_BODY,
                        KISAO_PROPERTY_PROPERTIES, KISAO_PROPERTY_RESPONSE));

        var answered = provider(transport).resolveAll(List.of(GO, KISAO_PROPERTY));

        assertEquals(3, transport.requests.size(), "one for the class, two for the property");
        assertEquals(2, answered.size());
        assertEquals("biological_process", firstLine(answered.get(GO).keywordText()));
        assertEquals("has characteristic", firstLine(answered.get(KISAO_PROPERTY).keywordText()));
    }

    @Test
    void aGoEntityTheSearchIndexMissedResolvesBecauseTheClassBodyExists() {
        // GO_0043241 answers 200 and is obsolete with a successor, so the lookup costs two class
        // requests since ANNO-1e: the cited id, then the successor whose text is indexed. The point of
        // this test is unchanged: an empty search capture is not evidence of a missing entity.
        var transport = classAnswer(Map.of(GO_MERGED_CLASS, 200, GO_SUCCESSOR_CLASS, 200),
                Map.of(GO_MERGED_CLASS, GO_NOT_IN_SEARCH_RESPONSE, GO_SUCCESSOR_CLASS, GO_SUCCESSOR_RESPONSE));

        var answered = provider(transport).resolveAll(List.of(GO_NOT_IN_SEARCH));

        assertEquals(List.of(BASE + GO_MERGED_CLASS, BASE + GO_SUCCESSOR_CLASS), transport.urls(),
                "GO_0043241 has an empty search capture and a 200 class body: no search, the class path only");
        assertEquals("GO_0043241",
                firstLine(answered.get(GO_NOT_IN_SEARCH).keywordText()),
                "the cited body's own label leads the text (D12)");
        assertTrue(Arrays.asList(answered.get(GO_NOT_IN_SEARCH).keywordText().split("\n"))
                        .contains("protein-containing complex disassembly"),
                "and the successor's text follows under the cited key");
        assertEquals(Status.RESOLVED, answered.get(GO_NOT_IN_SEARCH).status(),
                "a real entity must not become a definitive negative");
        assertFalse(GO_NOT_IN_SEARCH_RESPONSE.isBlank());
    }

    @Test
    void a404OnTheClassCallAndA404OnThePropertyCallIsADefinitiveNegativeInTwoRequests() {
        var transport = classAnswer(Map.of(GO_CLASS, 404, GO_PROPERTY, 404),
                Map.of(GO_CLASS, NOT_FOUND_BODY, GO_PROPERTY, NOT_FOUND_BODY));

        var definition = provider(transport).resolveAll(List.of(GO)).get(GO);

        assertNotNull(definition);
        assertEquals(List.of(BASE + GO_CLASS, BASE + GO_PROPERTY), transport.urls(),
                "the class 404 falls through to the property; the property 404 ends it");
        assertEquals(Status.UNRESOLVED, definition.status());
        assertEquals(FailureClass.DEFINITIVE, definition.failureClass());
        assertEquals("", definition.keywordText());
        assertFalse(definition.failureReason().contains(SECRET));
    }

    @Test
    void theReverseDirectionAClassFourOhFourInThePropertyPathStillFallsThroughToAProperty() {
        var transport = classAnswer(
                Map.of(KISAO_CLASS, 404, "/v2/ontologies/kisao/properties/" + KISAO_IRI_TWICE, 200),
                Map.of(KISAO_CLASS, KISAO_PROPERTY_NOT_FOUND,
                        "/v2/ontologies/kisao/properties/" + KISAO_IRI_TWICE, KISAO_RESPONSE));

        var definition = provider(transport).resolveAll(List.of(KISAO)).get(KISAO);

        assertEquals(List.of(BASE + KISAO_CLASS, BASE + "/v2/ontologies/kisao/properties/" + KISAO_IRI_TWICE),
                transport.urls(), "the class 404 alone triggers exactly one property request");
        assertEquals(Status.RESOLVED, definition.status(), "the property body serves the term");
    }

    // ---- 3. keyword text -----------------------------------------------------------------------

    @Test
    void theCapturedGoBodyHasOneDefinitionInTwoKeysAndFourSynonymsSevenLines() {
        var text = resolve(GO, classAnswer(GO_CLASS, GO_RESPONSE)).keywordText();
        var lines = text.split("\n", -1);

        assertEquals(7, lines.length, "label, two definitions, four synonyms:\n" + text);
        assertEquals("biological_process", lines[0]);
        assertTrue(lines[1].startsWith("Note that, in addition to forming the root of the biological process"),
                "definition (the rdfs:comment) first: " + lines[1]);
        assertTrue(lines[2].startsWith("A biological process is the execution of a genetically-encoded"),
                "then the IAO_0000115 object's value: " + lines[2]);
        assertEquals(List.of("biological process", "physiological process",
                "single organism process", "single-organism process"),
                List.of(lines[3], lines[4], lines[5], lines[6]), "then the four synonyms, in order");
    }

    @Test
    void theCapturedKisaoClassIsLabelDefinitionThenThreeAlternativeLabels() {
        var text = resolve(KISAO, classAnswer(KISAO_CLASS, KISAO_RESPONSE)).keywordText();

        assertEquals(KISAO_0000019_TEXT, text,
                "label, skos:definition, then altLabel[].value; this body has no plain definition, no "
                        + "synonym and no IAO_0000115, and the doubled space of the definition collapses");
    }

    @Test
    void theCapturedFmaBodyIsLabelOnlyOneLine() {
        var definition = resolve(FMA, classAnswer("/v2/ontologies/fma/classes/"
                + "http%253A%252F%252Fpurl.org%252Fsig%252Font%252Ffma%252Ffma62387", FMA_RESPONSE));

        assertEquals(Status.RESOLVED, definition.status());
        var lines = definition.keywordText().split("\n", -1);
        assertEquals(1, lines.length, definition.keywordText());
        assertEquals("Junctional sarcoplasmic reticulum of cardiac muscle cell", lines[0]);
    }

    /**
     * SBO's {@code rdfs:comment} carries a synonym stub on {@code SBO:0000323}. It is a name, not a
     * definition, and the stub word itself is not part of the term's text: the useful keyword is
     * {@code Kmp}. Reading the property as a definition also inflated the measurement's definition
     * coverage, which counted SBO as publishing a definition it does not publish.
     */
    @Test
    void anSboSynonymCommentBecomesANameWithoutTheStubWord() {
        var text = resolve(SBO_SYNONYM,
                classAnswer(SBO_SYNONYM_CLASS, SBO_SYNONYM_RESPONSE)).keywordText();

        assertEquals("Michaelis constant for product\nKmp", text,
                "label then the synonym; neither 'Synonym:' nor the stub remains in the text");
    }

    /**
     * The same property on {@code SBO:0000176} holds a genuine definition. A blanket "rdfs:comment is
     * a synonym" rule would be false, and this pair is what keeps the rule on the value's shape.
     */
    @Test
    void aPlainSboCommentStaysADefinition() {
        var lines = resolve(SBO_DEFINITION,
                classAnswer(SBO_DEFINITION_CLASS, SBO_DEFINITION_RESPONSE))
                .keywordText().split("\n", -1);

        assertEquals("biochemical reaction", lines[0]);
        assertTrue(lines[1].startsWith("An event involving one or more chemical entities"),
                "a comment without the synonym stub is the term's definition: " + lines[1]);
        assertEquals(2, lines.length, String.join("\n", lines));
    }

    @Test
    void keywordTextIsCappedAtTheConfiguredMaximum() {
        var provider = new Ols4TermProvider(
                BASE, classAnswer(GO_CLASS, GO_RESPONSE), catalogue, 12);

        var text = provider.resolveAll(List.of(GO)).get(GO).keywordText();

        assertEquals(12, text.length());
        assertEquals("biological_p", text, "a cap cuts inside a line rather than dropping the line");
    }

    @Test
    void aScalarAndAnObjectValueAreBothAcceptedAndTheObjectValueIsRead() {
        var detail = """
                {
                  "label": ["biological_process"],
                  "synonym": "a scalar synonym",
                  "definition": "one definition",
                  "http://www.w3.org/2004/02/skos/core#altLabel":
                    {"type":["reification"], "value": "an alt label"},
                  "http://purl.obolibrary.org/obo/IAO_0000115":
                    {"type":["reification"], "value": "an IAO definition"}
                }
                """;

        var text = resolve(GO, classAnswer(GO_CLASS, detail)).keywordText();

        assertEquals("""
                biological_process
                one definition
                an IAO definition
                a scalar synonym
                an alt label""", text,
                "scalar where a list is expected is accepted; an object contributes its value");
    }

    @Test
    void duplicateTextAcrossSourcesIsKeptOnceCaseInsensitively() {
        var detail = """
                {
                  "label": "CVODE",
                  "synonym": ["VODE", "cvode"],
                  "http://www.w3.org/2004/02/skos/core#definition": "  the   same   definition ",
                  "http://purl.obolibrary.org/obo/IAO_0000115": "The same definition",
                  "http://www.w3.org/2004/02/skos/core#altLabel": [
                    {"value": "VODE"}, {"value": "code value ordinary differential equation solver"}]
                }
                """;

        var text = resolve(KISAO, classAnswer(KISAO_CLASS, detail)).keywordText();

        assertEquals("""
                CVODE
                the same definition
                VODE
                code value ordinary differential equation solver""", text,
                "whitespace collapsed, the second spelling of a definition dropped, VODE kept once");
    }

    // ---- 4. failure ----------------------------------------------------------------------------

    @Test
    void aClassBodyWithoutALabelThrows() {
        var transport = classAnswer(GO_CLASS, "{\"iri\":\"" + GO_IRI + "\",\"curie\":\"GO:0008150\"}");
        var provider = provider(transport);

        var thrown = assertThrows(IllegalStateException.class, () -> provider.resolveAll(List.of(GO)));

        assertTrue(thrown.getMessage().contains("label"), thrown.getMessage());
        assertEquals(List.of(BASE + GO_CLASS), transport.urls(),
                "a missing label throws on the class answer; it never falls through to the property");
    }

    @Test
    void a500OnTheClassCallThrowsWithoutProbingTheProperty() {
        var transport = classAnswer(Map.of(GO_CLASS, 500), Map.of(GO_CLASS, "upstream unavailable"));

        var thrown = assertThrows(IllegalStateException.class,
                () -> provider(transport).resolveAll(List.of(GO)));

        assertTrue(thrown.getMessage().contains("500"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("class lookup"), thrown.getMessage());
        assertEquals(List.of(BASE + GO_CLASS), transport.urls(),
                "a 500 is a provider fault, never a fallback trigger: " + transport.urls());
    }

    @Test
    void a500OnThePropertyCallThrowsSoTheResolverDemotesInsteadOfRecordingANegative() {
        var transport = classAnswer(
                Map.of(KISAO_PROPERTY_CLASS, 404, KISAO_PROPERTY_PROPERTIES, 500),
                Map.of(KISAO_PROPERTY_CLASS, NOT_FOUND_BODY, KISAO_PROPERTY_PROPERTIES, "upstream unavailable"));

        var thrown = assertThrows(IllegalStateException.class,
                () -> provider(transport).resolveAll(List.of(KISAO_PROPERTY)));

        assertTrue(thrown.getMessage().contains("500"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("property lookup"), thrown.getMessage());
    }

    @Test
    void aServiceUnavailableIsTransientForThatKeyOnly() {
        var transport = classAnswer(
                Map.of(GO_CLASS, 503, KISAO_CLASS, 200),
                Map.of(GO_CLASS, "upstream unavailable", KISAO_CLASS, KISAO_RESPONSE));

        var answers = provider(transport).resolveAll(List.of(GO, KISAO));

        assertEquals(TermDefinition.FailureClass.TRANSIENT, answers.get(GO).failureClass(),
                "a 503 is worth retrying, not a definitive negative");
        assertEquals("", answers.get(GO).keywordText());
        assertEquals(TermDefinition.Status.RESOLVED, answers.get(KISAO).status(),
                "the other key is untouched by the refusal");
        assertEquals(2, answers.size(), "one entry per key asked: " + answers.keySet());
    }

    @Test
    void aBodyThatIsNotJsonOnEitherCallThrows() {
        assertThrows(IllegalStateException.class,
                () -> provider(classAnswer(GO_CLASS, "<html>not json</html>")).resolveAll(List.of(GO)));
        var transport = classAnswer(
                Map.of(KISAO_PROPERTY_CLASS, 404, KISAO_PROPERTY_PROPERTIES, 200),
                Map.of(KISAO_PROPERTY_CLASS, NOT_FOUND_BODY, KISAO_PROPERTY_PROPERTIES, "<html>not json</html>"));
        assertThrows(IllegalStateException.class,
                () -> provider(transport).resolveAll(List.of(KISAO_PROPERTY)));
    }

    @Test
    void aTransportFailureOnEitherCallThrows() {
        var onClass = assertThrows(IllegalStateException.class,
                () -> new Ols4TermProvider(BASE, request -> {
                    throw new IllegalStateException("connect timed out");
                }, catalogue, AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH).resolveAll(List.of(GO)));
        var onProperty = assertThrows(IllegalStateException.class,
                () -> new Ols4TermProvider(BASE, request -> {
                    if (request.url().contains("/properties/")) {
                        throw new IllegalStateException("read timed out");
                    }
                    return new ProviderResponse(404, NOT_FOUND_BODY, "application/json");
                }, catalogue, AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH).resolveAll(List.of(GO)));

        assertTrue(onClass.getMessage().length() > 0);
        assertTrue(onProperty.getMessage().length() > 0);
    }

    // ---- 5. nothing secret in a rendering ------------------------------------------------------

    @Test
    void noMessageUrlOrToStringCarriesAHeaderValue() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> provider(classAnswer(GO_CLASS, 500, "boom")).resolveAll(List.of(GO)));
        var notJson = assertThrows(IllegalStateException.class,
                () -> provider(classAnswer(GO_CLASS, "nope")).resolveAll(List.of(GO)));
        var transport = assertThrows(IllegalStateException.class,
                () -> new Ols4TermProvider(BASE, request -> {
                    throw new IllegalStateException("read timed out");
                }, catalogue, AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH).resolveAll(List.of(GO)));
        var rendered = new ProviderRequest("GET", BASE + GO_CLASS,
                Map.of("Authorization", SECRET, "Accept", "application/json"), null)
                .redacted(Set.of("Authorization"));

        for (var exception : List.of(thrown, notJson, transport)) {
            var message = String.valueOf(exception.getMessage());
            assertFalse(message.contains(SECRET), message);
        }
        assertTrue(String.valueOf(thrown.getMessage()).contains("GET " + BASE),
                "the failure still names the request that failed: " + thrown.getMessage());
        assertFalse(rendered.toString().contains(SECRET));
        assertFalse(rendered.redacted(Set.of("Authorization")).headers().get("Authorization").contains(SECRET));
        assertEquals(AnnotationProviderHttp.REDACTED, rendered.headers().get("Authorization"));
    }

    // ---- the exact texts -----------------------------------------------------------------------

    /** The measured text of {@code kisao:0000019}: label, the skos definition, then the three altLabels. */
    private static final String KISAO_0000019_TEXT = """
            CVODE
            The CVODE is a package written in C that solves ODE initial value problems, in real N-space, \
            written as y'=f(t,y), y(t0)=y0. It is capable for stiff and non-stiff systems and uses two \
            different linear multi-step methods, namely the Adam-Moulton \
            [http://identifiers.org/biomodels.kisao/KISAO_0000280] method and the backward differentiation \
            formula [http://identifiers.org/biomodels.kisao/KISAO_0000288].
            VODE
            VODEPK
            code value ordinary differential equation solver""";

    // ---- 6. obsolete and merged ids (D11) ------------------------------------------------------

    @Test
    void aMergedGoIdYieldsTheSuccessorsDefinitionUnderTheCitedKey() {
        var transport = classAnswer(Map.of(GO_MERGED_CLASS, 200, GO_SUCCESSOR_CLASS, 200),
                Map.of(GO_MERGED_CLASS, GO_MERGED_RESPONSE, GO_SUCCESSOR_CLASS, GO_SUCCESSOR_RESPONSE));

        var definition = resolve(GO_NOT_IN_SEARCH, transport);

        assertEquals(List.of(BASE + GO_MERGED_CLASS, BASE + GO_SUCCESSOR_CLASS), transport.urls(),
                "the cited id, then its successor through the same class path");
        assertEquals(Status.RESOLVED, definition.status(), "a merged id is a valid annotation, never a negative");
        assertEquals(GO_NOT_IN_SEARCH, definition.key(), "the cited id is never rewritten (D11)");
        assertEquals("go/0043241", definition.key().prefix() + "/" + definition.key().id());
        assertEquals("GO_0043241", firstLine(definition.keywordText()),
                "the cited body's own label leads the text (D12: what the citation says, first)");
        assertTrue(Arrays.asList(definition.keywordText().split("\n")).contains(GO_SUCCESSOR_DEFINITION),
                "the successor's definition is indexed under the cited id:\n" + definition.keywordText());
        assertTrue(definition.termStatus().obsolete(), "GO_0043241 is obsolete");
        assertEquals("GO:0032984", definition.termStatus().replacedBy(), "the successor as a curie");
        assertTrue(definition.termStatus().reason().contains("IAO_0000227"),
                "the reason IRI names terms merged: " + definition.termStatus().reason());
        assertEquals("ols4", definition.termStatus().source());
    }

    @Test
    void aMergedChebiIdYieldsTheSuccessorsDefinitionUnderTheCitedKey() {
        var transport = classAnswer(Map.of(CHEBI_1_CLASS, 200, CHEBI_18357_CLASS, 200),
                Map.of(CHEBI_1_CLASS, CHEBI_MERGED_RESPONSE, CHEBI_18357_CLASS, CHEBI_SUCCESSOR_RESPONSE));

        var definition = resolve(CHEBI_1, transport);

        assertEquals(Status.RESOLVED, definition.status());
        assertEquals(CHEBI_1, definition.key());
        assertEquals("CHEBI_1", firstLine(definition.keywordText()),
                "the cited body's own label leads (D12)");
        assertTrue(Arrays.asList(definition.keywordText().split("\n")).contains(CHEBI_SUCCESSOR_DEFINITION),
                "the successor's definition sentence:\n" + definition.keywordText());
        assertTrue(definition.termStatus().obsolete());
        assertEquals("CHEBI:18357", definition.termStatus().replacedBy());
        assertTrue(definition.termStatus().reason().contains("IAO_0000227"));
    }

    @Test
    void aMergedObsoleteIdIndexesItsOwnTextAsWellAsItsSuccessors() {
        // D12, decided 2026-11-16. A model citing GO:0000003 is annotated with "reproduction": a search
        // for "individuals" or "genetic material inherited" must still find that model, so the cited
        // term's own published text is indexed too - not only the successor's.
        var transport = classAnswer(
                Map.of(GO_OBSOLETE_MERGED_CLASS, 200, GO_REPRODUCTIVE_PROCESS_CLASS, 200),
                Map.of(GO_OBSOLETE_MERGED_CLASS, GO_OBSOLETE_MERGED_RESPONSE,
                        GO_REPRODUCTIVE_PROCESS_CLASS, GO_REPRODUCTIVE_PROCESS_RESPONSE));

        var definition = resolve(GO_OBSOLETE_MERGED, transport);
        var lines = Arrays.asList(definition.keywordText().split("\n"));

        assertEquals(List.of(BASE + GO_OBSOLETE_MERGED_CLASS, BASE + GO_REPRODUCTIVE_PROCESS_CLASS),
                transport.urls(), "the cited id, then its successor");
        assertEquals("obsolete reproduction", firstLine(definition.keywordText()),
                "the cited term's own label leads: it is what the model wrote");
        assertTrue(lines.contains(GO_OBSOLETE_DEFINITION),
                "the obsolete term's own definition stays searchable:\n" + definition.keywordText());
        assertTrue(lines.contains("reproductive process"), "the successor's label follows");
        assertTrue(lines.contains(GO_REPRODUCTIVE_DEFINITION), "and its definition");
        assertEquals(GO_OBSOLETE_MERGED, definition.key(), "the cited id is still the key");
        assertTrue(definition.termStatus().obsolete());
        assertEquals("GO:0022414", definition.termStatus().replacedBy());
    }

    @Test
    void anObsoleteTermWithoutASuccessorKeepsItsOwnTextAndReportsNoReplacement() {
        var transport = classAnswer(GO_OBSOLETE_CLASS, GO_OBSOLETE_RESPONSE);

        var definition = resolve(GO_OBSOLETE, transport);

        assertEquals(List.of(BASE + GO_OBSOLETE_CLASS), transport.urls(),
                "no successor to follow: exactly one request");
        assertEquals(Status.RESOLVED, definition.status());
        assertEquals("obsolete ribosomal chaperone activity", firstLine(definition.keywordText()),
                "an obsolete term keeps its own label and definition");
        assertTrue(Arrays.asList(definition.keywordText().split("\n"))
                        .contains("ribosomal chaperone activity"),
                "and its own synonyms:\n" + definition.keywordText());
        assertTrue(definition.termStatus().obsolete());
        assertNull(definition.termStatus().replacedBy(), "nothing replaced it");
        assertNull(definition.termStatus().reason());
    }

    @Test
    void aSuccessorThatFourOhFourStillResolvesTheCitedKeyFromItsOwnBody() {
        var transport = classAnswer(
                Map.of(GO_MERGED_CLASS, 200, GO_SUCCESSOR_CLASS, 404,
                        "/v2/ontologies/go/properties/" + GO_SUCCESSOR_IRI_TWICE, 404),
                Map.of(GO_MERGED_CLASS, GO_MERGED_RESPONSE, GO_SUCCESSOR_CLASS, NOT_FOUND_BODY,
                        "/v2/ontologies/go/properties/" + GO_SUCCESSOR_IRI_TWICE, NOT_FOUND_BODY));

        var definition = resolve(GO_NOT_IN_SEARCH, transport);

        assertEquals(Status.RESOLVED, definition.status(),
                "a successor that cannot be read never turns the cited term into a failure");
        assertNull(definition.failureClass());
        assertEquals("GO_0043241", firstLine(definition.keywordText()),
                "the cited body's own label is the floor");
        assertTrue(definition.termStatus().obsolete());
        assertEquals("GO:0032984", definition.termStatus().replacedBy(),
                "the successor is still reported even though its text could not be read");
    }

    @Test
    void aSuccessorThatFailsAtTransportLevelStillResolvesTheCitedKey() {
        var transport = new AnnotationProviderHttp() {
            @Override
            public ProviderResponse send(ProviderRequest request) {
                if (request.url().endsWith(GO_SUCCESSOR_CLASS)) {
                    throw new IllegalStateException("read timed out");
                }
                return new ProviderResponse(200, GO_MERGED_RESPONSE, "application/json");
            }
        };

        var definition = resolve(GO_NOT_IN_SEARCH, transport);

        assertEquals(Status.RESOLVED, definition.status(),
                "a transport failure on the successor is absorbed, not propagated");
        assertEquals("GO_0043241", firstLine(definition.keywordText()));
        assertEquals("GO:0032984", definition.termStatus().replacedBy());
    }

    @Test
    void anUntouchedMeaningfulTermCarriesTheEmptyStatus() {
        var definition = resolve(GO, classAnswer(GO_CLASS, GO_RESPONSE));

        assertEquals(TermStatus.none(), definition.termStatus());
        assertFalse(definition.termStatus().obsolete());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private Ols4TermProvider provider(AnnotationProviderHttp transport) {
        return new Ols4TermProvider(BASE, transport, catalogue, AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
    }

    private TermDefinition resolve(TermKey key, AnnotationProviderHttp transport) {
        var definition = provider(transport).resolveAll(List.of(key)).get(key);
        assertNotNull(definition, "no definition for " + key);
        return definition;
    }

    /** One class path that answers {@code 200} with a body. */
    private static PathTransport classAnswer(String path, String body) {
        return new PathTransport(Map.of(path, 200), Map.of(path, body));
    }

    /** One class path that answers {@code status} with a body. */
    private static PathTransport classAnswer(String path, int status, String body) {
        return new PathTransport(Map.of(path, status), Map.of(path, body));
    }

    /** Several paths, each with its own status and body. */
    private static PathTransport classAnswer(Map<String, Integer> statuses, Map<String, String> bodies) {
        return new PathTransport(statuses, bodies);
    }

    private static String firstLine(String text) {
        var newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** A captured body from the test classpath, byte for byte. */
    private static String fixture(String name) {
        var resource = "annotation/providers/" + name;
        try (InputStream stream = Ols4TermProviderTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * A transport that answers per path with its own status and body, and records every request: the
     * request economy of the class-then-property flow is the point of these tests.
     */
    private static final class PathTransport implements AnnotationProviderHttp {

        private final Map<String, Integer> statuses;
        private final Map<String, String> bodies;
        private final List<ProviderRequest> requests = new ArrayList<>();

        PathTransport(Map<String, Integer> statuses, Map<String, String> bodies) {
            this.statuses = new LinkedHashMap<>(statuses);
            this.bodies = new LinkedHashMap<>(bodies);
        }

        @Override
        public ProviderResponse send(ProviderRequest request) {
            requests.add(request);
            var path = request.url().substring(BASE.length());
            var status = statuses.get(path);
            if (status == null) {
                throw new IllegalStateException("unexpected request in the class-then-property flow: " + request);
            }
            return new ProviderResponse(status, bodies.get(path), "application/json");
        }

        List<String> urls() {
            return requests.stream().map(ProviderRequest::url).toList();
        }
    }
}

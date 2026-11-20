package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ANNO-1b's keyword-quality measurement, re-derived offline from the committed captures.
 *
 * <p>What is measured is <em>adapter fidelity</em>: whether {@link Ols4TermProvider} reproduces what
 * the published vocabulary says about a term — its names and its definitions. It is not a measure of
 * retrieval quality: each capture is already the right entity for its key, so a bad lookup could not
 * lower these numbers and a good one could not raise them.
 *
 * <p>The ground truth is {@code annotation/providers/expected-keywords.tsv}, transcribed by hand from
 * the published fields of those responses, including fields the adapter does not read, so the
 * expectation cannot inherit the adapter's blind spots. An expectation is never generated from adapter
 * output.
 *
 * <p>The history this file records, because both numbers are real measurements:
 *
 * <ul>
 *   <li>2026-11-13, first measurement, over the term endpoint: macro name recall <b>0.9318</b>,
 *       definition coverage <b>7 of 8</b>, term coverage <b>11 of 12</b>. The loss was one shape:
 *       {@code KISAO_0000019} keeps three of four names and its definition in OLS4's {@code annotation}
 *       block, and {@code KISAO_0000245} answered 404 from the class endpoint because it is an object
 *       property.
 *   <li>2026-11-14, after the type-aware v2 lookup: recall <b>1.0</b>, precision <b>1.0</b>,
 *       definition coverage <b>9 of 9</b>, term coverage <b>12 of 12</b>. The v2 read also restores the
 *       second definition of {@code GO_0008150} ({@code IAO_0000115}), which a naive v2 read would have
 *       dropped.
 *   <li>2026-11-16, after ANNO-1f: term coverage is unchanged at <b>12 of 12</b> and recall and
 *       precision stay <b>1.0</b>, while the definition denominator falls from 9 to <b>8</b> — not
 *       because a definition was lost, but because {@code SBO:0000323}'s {@code rdfs:comment} is the
 *       synonym stub {@code "Synonym: Kmp"}, which the adapter now routes to the name bucket. The
 *       measurement had been crediting SBO with a definition it does not publish.
 *   <li>2026-11-16, after ANNO-1g/ANNO-1h: the three {@code go} rows now come from QuickGO, and the merged
 *       id {@code GO_0043241} carries the D12 union (its label plus the successor's name, definition and
 *       synonyms). Term coverage stays <b>12 of 12</b> and recall and precision stay <b>1.0</b>; the
 *       definition denominator rises from 8 to <b>9</b> because the merged row now has a definition, and
 *       coverage stays <b>1.0</b> (9 of 9). On the old single-{@code NAME} ground truth for
 *       {@code go:0043241} this measurement is red — its precision is 1/7 — which is what makes the
 *       merged-id row visible.
 * </ul>
 *
 * <p><strong>Which provider answers.</strong> The three {@code go} samples no longer go to OLS4: they are
 * resolved through {@link QuickGoTermProvider}, the provider the term chain now wires for the GO prefix
 * (ANNO-1g), with the committed QuickGO captures as the body. The other nine keep {@link Ols4TermProvider}.
 * This makes the anchor a measurement of the provider the product uses for GO, not of a superceded one.
 *
 * <p><strong>The merged id (ANNO-1h).</strong> {@code go:0043241} is merged into {@code GO:0032984}:
 * QuickGO answers the requested accession with the successor's body and never names the requested id, so
 * the adapter produces the D12 union — the cited id's own OBO label first, then the successor's name,
 * definition and synonyms. Its expectation is that union, not the single old label, and this is the first
 * merged id the measurement sees. Only one GO sample is merged: {@code GO:0000003} (not sampled here) is
 * obsolete in place, not merged.
 *
 * <p>The OLS4 transport below answers a class read from the class capture and a property read from the
 * property capture, so the class-then-property fallback is part of what is measured: one of the twelve
 * terms is an object property whose class read 404s.
 *
 * <p>The measurement runs offline: no socket is opened. The constants are the measurement; changing one
 * is a decision about the adapter or the vocabulary, not a fix for a red test. The dated record with the
 * reasoning behind them is {@code docs/modernization/annotation-keyword-quality.md}.
 */
class AnnotationKeywordQualityTest {

    private static final String PROVIDERS = "annotation/providers/";
    private static final String QUICKGO_BASE = "https://quickgo.example";
    private static final String GO_PREFIX = "go";

    /**
     * The twelve captures, in the order the measurement reports them. The three {@code go} samples carry
     * their QuickGO capture (one single-accession answer each); every other entity is a class, and
     * {@code KISAO_0000245} is an object property, so its class capture is the 404 that makes the adapter
     * fall through to the property path. Hard-coded, never enumerated, so a capture added without an
     * expectation (or the reverse) fails loudly below.
     */
    private static final List<Sample> SAMPLE = List.of(
            Sample.quickGo("go:0008150", "quickgo-term-go-0008150.json"),
            Sample.quickGo("go:0043241", "quickgo-term-go-0043241.json"),
            Sample.quickGo("go:0051019", "quickgo-term-go-0051019.json"),
            Sample.ols4("chebi:33699", "ols4-v2-classes-chebi-33699.json", 200, null),
            Sample.ols4("chebi:16761", "ols4-v2-classes-chebi-16761.json", 200, null),
            Sample.ols4("chebi:16526", "ols4-v2-classes-chebi-16526.json", 200, null),
            Sample.ols4("fma:62387", "ols4-v2-classes-fma-62387.json", 200, null),
            Sample.ols4("fma:62390", "ols4-v2-classes-fma-62390.json", 200, null),
            Sample.ols4("sbo:0000323", "ols4-v2-classes-sbo-0000323.json", 200, null),
            Sample.ols4("sbo:0000176", "ols4-v2-classes-sbo-0000176.json", 200, null),
            Sample.ols4("kisao:0000019", "ols4-v2-classes-kisao-0000019.json", 200, null),
            Sample.ols4("kisao:0000245", "ols4-v2-classes-kisao-0000245.json", 404,
                    "ols4-v2-properties-kisao-0000245.json"));

    private static final int SAMPLE_SIZE = 12;
    private static final int RESOLVABLE_TERMS = 12;
    /**
     * Nine, not eight: the merged-id row {@code go:0043241} now has a DEFINITION (the successor's), which
     * the old single-label row never had. The other eight are unchanged.
     */
    private static final int TERMS_WITH_DEFINITIONS = 9;

    // ---- the measurement of 2026-11-14: pinned, not fixed up. Changing one is a decision. ----

    private static final double EXPECTED_MACRO_RECALL = 1.0;
    private static final double EXPECTED_MACRO_PRECISION = 1.0;
    private static final double EXPECTED_DEFINITION_COVERAGE = 1.0;
    private static final double EXPECTED_TERM_COVERAGE = 1.0;
    private static final double TOLERANCE = 0.0001;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void measurementMatchesAnno1b() throws IOException {
        var measured = measure(loadExpectations());

        assertEquals(SAMPLE_SIZE, measured.terms().size(), "one measurement per sample");
        for (var term : measured.terms()) {
            assertTrue(term.resolved(), term.termKey() + " must resolve: every sampled entity exists");
            assertEquals(1.0, term.nameRecall(), TOLERANCE, "nameRecall of " + term.termKey());
            assertEquals(1.0, term.precision(), TOLERANCE, "precision of " + term.termKey());
        }

        assertEquals(EXPECTED_MACRO_RECALL, measured.macroRecall(), TOLERANCE, "macroRecall");
        assertEquals(EXPECTED_MACRO_PRECISION, measured.macroPrecision(), TOLERANCE, "macroPrecision");
        assertEquals(EXPECTED_DEFINITION_COVERAGE, measured.definitionCoverage(), TOLERANCE, "definitionCoverage");
        assertEquals(EXPECTED_TERM_COVERAGE, measured.termCoverage(), TOLERANCE, "termCoverage");

        assertEquals(RESOLVABLE_TERMS, measured.resolvable(), "resolvable terms");
        assertEquals(TERMS_WITH_DEFINITIONS, measured.termsWithDefinitions(), "terms that have DEFINITION rows");
        assertEquals(TERMS_WITH_DEFINITIONS, measured.definitionCoveredTerms(), "terms whose definitions are all produced");

        System.out.println("ANNO-1b macroRecall=" + measured.macroRecall()
                + " macroPrecision=" + measured.macroPrecision()
                + " definitionCoverage=" + measured.definitionCoverage()
                + " termCoverage=" + measured.termCoverage());
    }

    /**
     * The gap the first measurement recorded, now closed and kept as an assertion so it cannot reopen
     * silently: {@code KISAO_0000245} is an object property, and the class endpoint's 404 was our wrong
     * question, not a provider gap.
     */
    @Test
    void theKisaoPropertyResolvesAndCarriesItsNameAndDefinition() throws IOException {
        var key = keyOf("kisao:0000245");
        var definition = providerFor("kisao:0000245")
                .resolveAll(List.of(key)).get(key);

        assertNotNull(definition, "the property must produce a definition");
        assertTrue(definition.resolved(), "an objectProperty is resolvable: " + definition.failureReason());
        assertTrue(definition.keywordText().startsWith("has characteristic\n"), definition.keywordText());
        assertTrue(definition.keywordText().contains("links algorithms to the characteristics"),
                definition.keywordText());
    }

    /**
     * QuickGO publishes {@code GO:0008150}'s two texts in two fields — {@code definition.text} and the
     * {@code comment} — and the adapter keeps both, after the name. This is the assertion that keeps both,
     * in the provider the GO prefix now uses.
     */
    @Test
    void theGoRootKeepsBothOfItsDefinitions() throws IOException {
        var key = keyOf("go:0008150");
        var definition = providerFor("go:0008150")
                .resolveAll(List.of(key)).get(key);

        var produced = lines(definition.keywordText());
        assertEquals(7, produced.size(), "name, definition, comment and four synonyms: " + produced);
        assertTrue(produced.get(0).startsWith("biological_process"), "first line is the name: " + produced);
        assertTrue(produced.get(1).startsWith("a biological process is the execution"),
                "second line is the definition: " + produced);
        assertTrue(produced.get(2).startsWith("note that"), "third line is the comment: " + produced);
    }

    // ---- measurement -------------------------------------------------------------------------

    private static Measurement measure(Map<String, List<ExpectedRow>> expectations) throws IOException {
        var terms = new ArrayList<TermMeasurement>();
        for (var sample : SAMPLE) {
            var key = keyOf(sample.termKey());
            var definition = providerFor(sample).resolveAll(List.of(key)).get(key);
            assertNotNull(definition, "provider answered no definition for " + sample.termKey());

            var produced = lines(definition.keywordText());
            var expectedNames = new LinkedHashSet<String>();
            var expectedDefinitions = new LinkedHashSet<String>();
            for (var row : expectations.get(sample.termKey())) {
                if (row.kind() == Kind.NAME) {
                    expectedNames.add(normalize(row.text()));
                } else if (row.kind() == Kind.DEFINITION) {
                    expectedDefinitions.add(normalize(row.text()));
                }
            }

            var matchedNames = 0;
            for (var name : expectedNames) {
                if (produced.contains(name)) {
                    matchedNames++;
                }
            }
            var relevant = 0;
            for (var line : produced) {
                if (expectedNames.contains(line) || expectedDefinitions.contains(line)) {
                    relevant++;
                }
            }
            var covered = !expectedDefinitions.isEmpty() && produced.containsAll(expectedDefinitions);
            terms.add(new TermMeasurement(
                    sample.termKey(),
                    definition.resolved(),
                    expectedNames.isEmpty() ? 1.0 : (double) matchedNames / expectedNames.size(),
                    produced.isEmpty() ? 0.0 : (double) relevant / produced.size(),
                    expectedDefinitions.isEmpty() ? null : covered));
        }

        double recallSum = 0.0;
        double precisionSum = 0.0;
        int resolvable = 0;
        int withDefinitions = 0;
        int coveredTerms = 0;
        for (var term : terms) {
            if (!term.resolved()) {
                continue;
            }
            resolvable++;
            recallSum += term.nameRecall();
            precisionSum += term.precision();
            if (term.definitionCovered() != null) {
                withDefinitions++;
                if (term.definitionCovered()) {
                    coveredTerms++;
                }
            }
        }
        return new Measurement(
                List.copyOf(terms),
                resolvable == 0 ? 0.0 : recallSum / resolvable,
                resolvable == 0 ? 0.0 : precisionSum / resolvable,
                withDefinitions == 0 ? 1.0 : (double) coveredTerms / withDefinitions,
                (double) resolvable / SAMPLE.size(),
                resolvable,
                withDefinitions,
                coveredTerms);
    }

    private static AnnotationTermProvider providerFor(String termKey) throws IOException {
        var sample = SAMPLE.stream()
                .filter(candidate -> candidate.termKey().equals(termKey))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no sample for " + termKey));
        return providerFor(sample);
    }

    /**
     * The provider the term chain wires for the sample's prefix: QuickGO for {@code go} (its capture is one
     * single-accession batch answer, served when the request names that accession), OLS4 for everything
     * else.
     */
    private static AnnotationTermProvider providerFor(Sample sample) throws IOException {
        if (sample.quickGoFixture() != null) {
            return quickGoProviderFor(sample);
        }
        return ols4ProviderFor(sample);
    }

    /**
     * The QuickGO transport: the sample's capture when the request names its accession, and a loud failure
     * for anything else. The provider asks {@code {base}/services/ontology/go/terms/GO:<id>} for one key.
     */
    private static AnnotationTermProvider quickGoProviderFor(Sample sample) throws IOException {
        var body = fixture(sample.quickGoFixture());
        var id = sample.termKey().substring(sample.termKey().indexOf(':') + 1);
        var accession = "GO:" + id.toUpperCase(Locale.ROOT);
        return new QuickGoTermProvider(
                QUICKGO_BASE,
                request -> {
                    var url = request.url();
                    if (!url.contains(accession)) {
                        throw new IllegalStateException(
                                "unexpected QuickGO request for " + sample.termKey() + ": " + url);
                    }
                    return new AnnotationProviderHttp.ProviderResponse(200, body, "application/json");
                },
                AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
    }

    /**
     * The OLS4 transport of the fallback flow: the class read answers with the sample's class fixture and its
     * recorded status, and a {@code /properties/} read — which only a 404 on the class path can trigger —
     * with the property fixture. Any other URL is a bug in the adapter, so it fails loudly rather than
     * inventing a body.
     */
    private static AnnotationTermProvider ols4ProviderFor(Sample sample) throws IOException {
        var classBody = fixture(sample.classFixture());
        var propertyBody = sample.propertyFixture() == null ? null : fixture(sample.propertyFixture());
        return new Ols4TermProvider(
                "https://ols4.example",
                request -> {
                    var url = request.url();
                    if (url.contains("/properties/")) {
                        if (propertyBody == null) {
                            throw new IllegalStateException("unexpected property request: " + url);
                        }
                        return new AnnotationProviderHttp.ProviderResponse(200, propertyBody, "application/json");
                    }
                    if (!url.contains("/classes/")) {
                        throw new IllegalStateException("unexpected OLS4 request: " + url);
                    }
                    return new AnnotationProviderHttp.ProviderResponse(
                            sample.classStatus(), classBody, "application/json");
                },
                AnnotationPrefixCatalogue.standard(),
                AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
    }

    // ---- ground truth ------------------------------------------------------------------------

    private static Map<String, List<ExpectedRow>> loadExpectations() throws IOException {
        var text = resource(PROVIDERS + "expected-keywords.tsv");
        var byTerm = new LinkedHashMap<String, List<ExpectedRow>>();
        var seenRows = new LinkedHashSet<String>();
        var lineNumber = 0;
        for (var line : text.split("\n", -1)) {
            lineNumber++;
            var trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            var columns = line.split("\t", -1);
            final var current = lineNumber;
            assertEquals(3, columns.length,
                    "expected-keywords.tsv line " + lineNumber + " must have exactly 3 columns: " + line);
            var termKey = columns[0].strip();
            var kind = Kind.parse(columns[1].strip())
                    .orElseThrow(() -> new AssertionError(
                            "expected-keywords.tsv line " + current + ": unknown kind " + columns[1].strip()));
            var value = columns[2];
            assertTrue(seenRows.add(termKey + "\t" + kind + "\t" + normalize(value)),
                    "expected-keywords.tsv line " + lineNumber + " is a duplicate (key, kind, text) row");
            byTerm.computeIfAbsent(termKey, ignored -> new ArrayList<>()).add(new ExpectedRow(termKey, kind, value));
        }

        var sampled = new LinkedHashSet<String>();
        for (var sample : SAMPLE) {
            sampled.add(sample.termKey());
        }
        for (var termKey : byTerm.keySet()) {
            assertTrue(sampled.contains(termKey), "expectation key " + termKey + " is not in SAMPLE");
        }
        for (var termKey : sampled) {
            assertTrue(byTerm.containsKey(termKey), "SAMPLE key " + termKey + " has no expectation rows");
        }
        return byTerm;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static TermKey keyOf(String termKey) {
        var parts = termKey.split(":", 2);
        assertTrue(parts.length == 2, "term key must be prefix:id: " + termKey);
        return new TermKey(parts[0], parts[1]);
    }

    /** The produced keyword text as lines, each normalized; empty lines are dropped. */
    private static List<String> lines(String keywordText) {
        var lines = new ArrayList<String>();
        for (var line : keywordText.split("\n", -1)) {
            var normalized = normalize(line);
            if (!normalized.isEmpty()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    /** Case-insensitive, with every run of whitespace collapsed to one space. */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static String fixture(String fileName) throws IOException {
        return resource(PROVIDERS + fileName);
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = AnnotationKeywordQualityTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, "missing test resource " + name);
            assertFalse(name.isEmpty(), "resource name must not be empty");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- values ------------------------------------------------------------------------------

    /**
     * One hard-coded capture: the term it answers for and, depending on the provider that answers it, either
     * the QuickGO single-accession body or the v2 class body with its status and optional property body.
     */
    private record Sample(
            String termKey,
            String quickGoFixture,
            String classFixture,
            int classStatus,
            String propertyFixture
    ) {

        static Sample quickGo(String termKey, String quickGoFixture) {
            return new Sample(termKey, quickGoFixture, null, 0, null);
        }

        static Sample ols4(String termKey, String classFixture, int classStatus, String propertyFixture) {
            return new Sample(termKey, null, classFixture, classStatus, propertyFixture);
        }
    }

    private enum Kind {
        NAME,
        DEFINITION,
        UNRESOLVED;

        static Optional<Kind> parse(String value) {
            for (var kind : values()) {
                if (kind.name().equals(value)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    private record ExpectedRow(String termKey, Kind kind, String text) {
    }

    private record TermMeasurement(
            String termKey,
            boolean resolved,
            double nameRecall,
            double precision,
            Boolean definitionCovered
    ) {
    }

    private record Measurement(
            List<TermMeasurement> terms,
            double macroRecall,
            double macroPrecision,
            double definitionCoverage,
            double termCoverage,
            int resolvable,
            int termsWithDefinitions,
            int definitionCoveredTerms
    ) {
    }
}

package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AnnotationPrefixCatalogueTest {

    private final AnnotationPrefixCatalogue catalogue = AnnotationPrefixCatalogue.standard();

    @Test
    void canonicalPrefixesAndAliasesFromTheCensus() {
        var expected = Map.of(
                "go", "go",
                "obo.go", "go",
                "chebi", "chebi",
                "obo.chebi", "chebi",
                "fma", "fma",
                "sbo", "sbo",
                "biomodels.sbo", "sbo",
                "kisao", "kisao",
                "biomodels.kisao", "kisao");

        expected.forEach((spelling, canonical) -> {
            var entry = catalogue.entry(spelling);
            assertTrue(entry.isPresent(), spelling);
            assertEquals(canonical, entry.get().canonicalPrefix(), spelling);
            assertTrue(entry.get().ontological(), spelling);
            assertTrue(catalogue.isOntological(spelling), spelling);
        });
    }

    @Test
    void everyAliasPairFromTheCensusResolvesToItsCanonicalEntry() {
        assertEquals(catalogue.entry("go"), catalogue.entry("obo.go"));
        assertEquals(catalogue.entry("kisao"), catalogue.entry("biomodels.kisao"));
        assertEquals(catalogue.entry("sbo"), catalogue.entry("biomodels.sbo"));

        assertFalse(catalogue.entry("fma").get().aliases().contains("obo.fma"));
    }

    @Test
    void entryLookupIsCaseInsensitive() {
        assertEquals("go", catalogue.entry("GO").get().canonicalPrefix());
        assertEquals("kisao", catalogue.entry("BioModels.KISAO").get().canonicalPrefix());
    }

    @Test
    void miriamUrnWithEncodedAndPlainIds() {
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("urn:miriam:go:0008150"));
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("urn:miriam:obo.go:GO%3A0008150"));
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("urn:miriam:obo.go:GO_0008150"));
        assertEquals(Optional.of(new TermKey("sbo", "0000001")),
                catalogue.termKey("urn:miriam:biomodels.sbo:SBO_0000001"));
    }

    @Test
    void bothKisaoIdFormsYieldOneTermKey() {
        assertEquals(Optional.of(new TermKey("kisao", "0000019")),
                catalogue.termKey("urn:miriam:biomodels.kisao:KISAO_0000019"));
        assertEquals(Optional.of(new TermKey("kisao", "0000019")),
                catalogue.termKey("urn:miriam:biomodels.kisao:KISAO:0000019"));
        assertTrue(catalogue.termKey("urn:miriam:miriam.kisao:KISAO%3A0000019").isEmpty());
    }

    @Test
    void identifiersOrgPathsInBothSpellings() {
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("https://identifiers.org/go/0008150"));
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("https://identifiers.org/go:0008150"));
        assertEquals(Optional.of(new TermKey("chebi", "15377")),
                catalogue.termKey("https://identifiers.org/chebi/CHEBI:15377"));
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("http://identifiers.org/go/0008150/"));
    }

    @Test
    void oboNamespaceSpellingTakesThePrefixFromTheId() {
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("https://identifiers.org/obo/GO_0008150"));
        assertEquals(Optional.of(new TermKey("chebi", "15377")),
                catalogue.termKey("https://identifiers.org/obo/CHEBI_15377"));
    }

    @Test
    void directPurlUrls() {
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("http://purl.obolibrary.org/obo/GO_0008150"));
        assertEquals(Optional.of(new TermKey("go", "0008150")),
                catalogue.termKey("https://purl.obolibrary.org/obo/GO_0008150"));
        assertEquals(Optional.of(new TermKey("chebi", "15377")),
                catalogue.termKey("http://purl.obolibrary.org/obo/CHEBI_15377"));
        assertEquals(Optional.of(new TermKey("sbo", "0000001")),
                catalogue.termKey("http://purl.obolibrary.org/obo/SBO_0000001"));
    }

    @Test
    void termKeyFromPrefixAndLocalIdIgnoresThePrefixSpelling() {
        assertEquals(Optional.of(new TermKey("go", "0008150")), catalogue.termKey("GO:0008150"));
        assertEquals(Optional.of(new TermKey("go", "0008150")), catalogue.termKey("GO_0008150"));
        assertEquals(Optional.of(new TermKey("go", "0008150")), catalogue.termKey("obo.go", "GO:0008150"));
        assertEquals(Optional.of(new TermKey("kisao", "0000019")), catalogue.termKey("kisao", "KISAO_0000019"));
        assertEquals(Optional.of(new TermKey("kisao", "0000019")), catalogue.termKey("kisao", "KISAO:0000019"));
    }

    @Test
    void nonOntologicalPrefixesYieldNoTermKey() {
        for (var prefix : List.of("taxonomy", "uniprot", "pubmed", "kegg.pathway", "ec-code")) {
            assertFalse(catalogue.isOntological(prefix), prefix);
            assertTrue(catalogue.entry(prefix).isEmpty(), prefix);
        }
        assertTrue(catalogue.termKey("urn:miriam:taxonomy:9606").isEmpty());
        assertTrue(catalogue.termKey("urn:miriam:uniprot:P12345").isEmpty());
        assertTrue(catalogue.termKey("urn:miriam:pubmed:11429446").isEmpty());
        assertTrue(catalogue.termKey("taxonomy", "9606").isEmpty());
    }

    @Test
    void unknownPrefixesAndPlainPagesYieldNoTermKey() {
        assertFalse(catalogue.isOntological("nosuchontology"));
        assertTrue(catalogue.entry("nosuchontology").isEmpty());
        assertTrue(catalogue.entry(null).isEmpty());
        assertTrue(catalogue.entry("  ").isEmpty());

        assertTrue(catalogue.termKey("https://www.ebi.ac.uk/chebi/searchId.do?chebiId=CHEBI:15377").isEmpty());
        assertTrue(catalogue.termKey("https://example.org/model.xml").isEmpty());
        assertTrue(catalogue.termKey("https://identifiers.org/nosuchontology:1").isEmpty());
        assertTrue(catalogue.termKey("urn:miriam:").isEmpty());
        assertTrue(catalogue.termKey("urn:miriam:go").isEmpty());
        assertTrue(catalogue.termKey(null).isEmpty());
        assertTrue(catalogue.termKey("  ").isEmpty());
    }

    @Test
    void classIrisForEveryOntologyAreTheOnesBothProvidersAnswer() {
        // Measured 2026-11-13 against data.bioontology.org and ebi.ac.uk/ols4: both providers answer
        // these five forms with HTTP 200, and both answer 404 for the OBO purl of fma and sbo.
        assertEquals(Optional.of("http://purl.obolibrary.org/obo/GO_0008150"),
                catalogue.classIri(new TermKey("go", "0008150")));
        assertEquals(Optional.of("http://purl.obolibrary.org/obo/CHEBI_15377"),
                catalogue.classIri(new TermKey("chebi", "15377")));
        assertEquals(Optional.of("http://purl.org/sig/ont/fma/fma62387"),
                catalogue.classIri(new TermKey("fma", "62387")));
        assertEquals(Optional.of("http://biomodels.net/SBO/SBO_0000323"),
                catalogue.classIri(new TermKey("sbo", "0000323")));
        assertEquals(Optional.of("http://www.biomodels.net/kisao/KISAO#KISAO_0000019"),
                catalogue.classIri(new TermKey("kisao", "0000019")));
    }

    @Test
    void theLookupFingerprintChangesWithTheTemplateAndWithTheOntologyIds() {
        var go = catalogue.lookupFingerprint(new TermKey("go", "0008150")).orElseThrow();
        var sbo = catalogue.lookupFingerprint(new TermKey("sbo", "0000323")).orElseThrow();

        assertTrue(go.contains("http://purl.obolibrary.org/obo/GO_{id}") || go.contains("GO_"), go);
        assertTrue(sbo.contains("http://biomodels.net/SBO/SBO_{id}") || sbo.contains("SBO_"), sbo);
        assertTrue(go.contains("GO"), "the BioPortal acronym is part of the fingerprint: " + go);
        assertTrue(go.contains("|go"), "the OLS4 ontology id is part of the fingerprint: " + go);
        assertNotEquals(go, sbo);
        assertEquals(go, catalogue.lookupFingerprint(new TermKey("go", "0008150")).orElseThrow(),
                "the same term yields the same fingerprint");
        assertTrue(catalogue.lookupFingerprint(new TermKey("kegg", "hsa00010")).isEmpty(),
                "a non-ontological prefix has nothing to look up");
    }

    @Test
    void bioportalOntologyAcronymsMatchTheTable() {
        assertEquals(Optional.of("GO"), catalogue.bioportalOntology(new TermKey("go", "0008150")));
        assertEquals(Optional.of("CHEBI"), catalogue.bioportalOntology(new TermKey("chebi", "15377")));
        assertEquals(Optional.of("FMA"), catalogue.bioportalOntology(new TermKey("fma", "11449")));
        assertEquals(Optional.of("SBO"), catalogue.bioportalOntology(new TermKey("sbo", "0000001")));
        assertEquals(Optional.of("KISAO"), catalogue.bioportalOntology(new TermKey("kisao", "0000019")));
    }

    @Test
    void ols4OntologyIdsForEveryOntology() {
        assertEquals(Optional.of("go"), catalogue.ols4Ontology(new TermKey("go", "0008150")));
        assertEquals(Optional.of("chebi"), catalogue.ols4Ontology(new TermKey("chebi", "15377")));
        assertEquals(Optional.of("fma"), catalogue.ols4Ontology(new TermKey("fma", "11449")));
        assertEquals(Optional.of("sbo"), catalogue.ols4Ontology(new TermKey("sbo", "0000001")));
        assertEquals(Optional.of("kisao"), catalogue.ols4Ontology(new TermKey("kisao", "0000019")));
    }

    @Test
    void canonicalPageUrlForEveryOntology() {
        // Measured 2026-11-16 against resolver.api.identifiers.org: exactly these five compact ids
        // answer 200. `<prefix>:<id>` answers 400 for four of the five (namespaceEmbeddedInLui).
        assertEquals(Optional.of("https://identifiers.org/GO:0008150"),
                catalogue.canonicalPageUrl(new TermKey("go", "0008150")));
        assertEquals(Optional.of("https://identifiers.org/CHEBI:15377"),
                catalogue.canonicalPageUrl(new TermKey("chebi", "15377")));
        assertEquals(Optional.of("https://identifiers.org/FMA:62387"),
                catalogue.canonicalPageUrl(new TermKey("fma", "62387")));
        assertEquals(Optional.of("https://identifiers.org/SBO:0000323"),
                catalogue.canonicalPageUrl(new TermKey("sbo", "0000323")));
        assertEquals(Optional.of("https://identifiers.org/biomodels.kisao:KISAO_0000019"),
                catalogue.canonicalPageUrl(new TermKey("kisao", "0000019")));
    }

    @Test
    void theCommittedRegistryFixturesPinEveryCompactIdentifier() throws Exception {
        // The captured namespaces, keyed by canonical prefix. Each row must build a local id that its
        // own captured `pattern` accepts, and `namespaceEmbeddedInLui` must decide whether the page URL
        // carries the prefix: a namespace that embeds it in the LUI rejects `<prefix>:<id>` with 400.
        var accessions = Map.of(
                "go", new TermKey("go", "0008150"),
                "chebi", new TermKey("chebi", "15377"),
                "fma", new TermKey("fma", "62387"),
                "sbo", new TermKey("sbo", "0000323"),
                "kisao", new TermKey("kisao", "0000019"));
        var fixtures = Map.of(
                "go", "registry-find-by-prefix-go.json",
                "chebi", "registry-find-by-prefix-chebi.json",
                "fma", "registry-find-by-prefix-fma.json",
                "sbo", "registry-find-by-prefix-sbo.json",
                "kisao", "registry-find-by-prefix-biomodels-kisao.json");

        assertEquals(5, fixtures.size());
        for (var row : catalogue.ontologies()) {
            var prefix = row.canonicalPrefix();
            var key = accessions.get(prefix);
            assertNotNull(key, "no captured accession for " + prefix);
            var registry = registryFixture(fixtures.get(prefix));

            // The captured namespace is not always the canonical prefix: KiSAO's namespace is
            // "biomodels.kisao", which the catalogue knows as an alias. Comparing against the
            // canonical prefix would fail on exactly that row — and a rewrite that made the
            // fixture agree with the code instead would hide a namespace the registry does not have.
            var registryPrefix = registry.get("prefix").asText();
            assertEquals(prefix, catalogue.entry(registryPrefix).orElseThrow().canonicalPrefix(),
                    prefix + ": the captured namespace resolves to this catalogue row");
            assertEquals(false, registry.get("deprecated").asBoolean(), prefix + " must not be deprecated");

            var pattern = Pattern.compile(registry.get("pattern").asText());
            var embedded = registry.get("namespaceEmbeddedInLui").asBoolean();

            var compact = row.compactIdentifierTemplate().replace("{id}", key.id());
            // namespaceEmbeddedInLui decides the shape of the compact id: an embedded namespace is part
            // of the local id ("GO:0008150"), a non-embedded one is a prefix segment
            // ("biomodels.kisao:KISAO_0000019"). The reverse is the 400 the resolver answered.
            var namespaced = compact.startsWith(registryPrefix.concat(":"));
            assertEquals(!embedded, namespaced,
                    prefix + ": namespaceEmbeddedInLui decides whether the compact id carries the namespace");
            var lui = namespaced ? compact.substring(registryPrefix.length() + 1) : compact;

            assertTrue(pattern.matcher(lui).matches(),
                    prefix + ": local id '" + lui + "' does not match the captured pattern " + pattern);

            // The page URL is the base plus the compact identifier — no namespace segment invented by
            // the code: `<prefix>:<id>` is exactly the rejected form for an embedded namespace.
            var page = catalogue.canonicalPageUrl(key).orElseThrow();
            assertEquals("https://identifiers.org/" + compact, page,
                    prefix + ": the page URL is the base plus the compact identifier");
            if (embedded) {
                assertNotEquals("https://identifiers.org/" + prefix + ":" + key.id(), page,
                        prefix + ": the bare canonical prefix is the rejected form");
            }

            // And the captured resolver answer names exactly this namespace and local id.
            var resolved = registryFixture(resolverFixture(prefix))
                    .get("payload").get("parsedCompactIdentifier");
            assertEquals(registryPrefix, resolved.get("namespace").asText(), prefix + ": resolver namespace");
            assertEquals(lui, resolved.get("localId").asText(), prefix + ": resolver localId");
            assertEquals(embedded, resolved.get("namespaceEmbeddedInLui").asBoolean(),
                    prefix + ": the registry and the resolver agree on namespaceEmbeddedInLui");
        }
        assertEquals("GO:{id}", catalogue.entry("go").orElseThrow().compactIdentifierTemplate(),
                "the template is a literal {id} substitution, not a second round of interpolation");
    }

    private static String resolverFixture(String prefix) {
        return switch (prefix) {
            case "go" -> "resolver-GO-0008150.json";
            case "chebi" -> "resolver-CHEBI-15377.json";
            case "fma" -> "resolver-FMA-62387.json";
            case "sbo" -> "resolver-SBO-0000323.json";
            case "kisao" -> "resolver-biomodels-kisao-KISAO-0000019.json";
            default -> throw new IllegalArgumentException("no resolver fixture for " + prefix);
        };
    }

    @Test
    void pageTextIsAvailableForChebiOnly() {
        // Measured 2026-11-16: ChEBI is server-rendered; GO answers a plain client 403, the FMA and
        // KiSAO pages are OLS4 SPA shells, and SBO redirects to a resource deprecation notice.
        assertEquals(Optional.of(true), catalogue.pageTextAvailable(new TermKey("chebi", "15377")));
        for (var key : List.of(new TermKey("go", "0008150"), new TermKey("fma", "62387"),
                new TermKey("sbo", "0000323"), new TermKey("kisao", "0000019"))) {
            assertEquals(Optional.of(false), catalogue.pageTextAvailable(key), key.wire());
        }
        assertTrue(catalogue.pageTextAvailable(new TermKey("kegg", "hsa00010")).isEmpty(),
                "a non-ontological prefix has no page answer");
    }

    /**
     * FMA's text source is BioPortal alone. OLS4's FMA configuration indexes no definition property
     * and no synonym property (captured: {@code ols4-v2-ontologies-fma.json}), so an OLS4 answer is a
     * label and nothing else — measured on {@code FMA:305751}, whose BioPortal class carries a
     * definition and the synonym "Biological structure" while OLS4 carries only its label.
     *
     * <p>The declaration is not cosmetic: it is what lets a run with no configured source say "no
     * text source configured for this prefix" instead of asserting that FMA publishes no definitions.
     */
    @Test
    void fmaHasBioPortalAsItsOnlyDefinitionSource() {
        assertEquals(Set.of(TermDefinition.Source.BIOPORTAL),
                catalogue.definitionSources(new TermKey("fma", "305751")));

        for (var key : List.of(new TermKey("go", "0008150"), new TermKey("chebi", "15377"),
                new TermKey("sbo", "0000323"), new TermKey("kisao", "0000019"))) {
            assertEquals(Set.of(TermDefinition.Source.BIOPORTAL, TermDefinition.Source.OLS4),
                    catalogue.definitionSources(key), key.wire());
        }
        assertTrue(catalogue.definitionSources(new TermKey("kegg", "hsa00010")).isEmpty(),
                "a non-ontological prefix has no configured source");
    }

    /**
     * The captured pair behind the declaration: OLS4's FMA configuration indexes neither a definition
     * property nor a synonym property, and the same term at the two providers shows the consequence —
     * OLS4 answers "Anatomical structure" alone, BioPortal adds the definition and "Biological
     * structure". A test of the pair is what keeps "FMA has no definitions" from coming back.
     */
    @Test
    void theCapturedFmaPairShowsWhyTheDeclarationExists() throws Exception {
        var config = providerFixture("ols4-ontologies-fma.json").get("config");
        assertTrue(config.get("definitionProperties").isEmpty(),
                "OLS4's FMA config indexes no definition property");
        assertTrue(config.get("synonymProperties").isEmpty(),
                "OLS4's FMA config indexes no synonym property");

        var ols4 = providerFixture("ols4-v2-classes-fma-305751.json");
        assertEquals("Anatomical structure", ols4.get("label").get(0).asText());
        assertTrue(ols4.get("definition") == null || ols4.get("definition").isEmpty(),
                "OLS4 carries no definition for the term");
        assertTrue(ols4.get("synonym") == null || ols4.get("synonym").isEmpty(),
                "OLS4 carries no synonym for the term");

        var bioportal = providerFixture("bioportal-class-fma-305751.json");
        assertEquals("Anatomical structure", bioportal.get("prefLabel").asText());
        assertFalse(bioportal.get("definition").isEmpty(), "BioPortal carries the definition");
        assertEquals("Biological structure", bioportal.get("synonym").get(0).asText());
    }

    /** A captured provider body from {@code annotation/providers/}. */
    private static JsonNode providerFixture(String name) throws Exception {
        var resource = AnnotationPrefixCatalogue.class.getResource("/annotation/providers/" + name);
        assertNotNull(resource, "missing fixture " + name);
        try (var stream = resource.openStream()) {
            return new ObjectMapper().readTree(stream);
        }
    }

    private static JsonNode registryFixture(String name) throws Exception {
        var resource = AnnotationPrefixCatalogue.class.getResource("/annotation/identifiers-org/" + name);
        assertNotNull(resource, "missing fixture " + name);
        try (var stream = resource.openStream()) {
            return new ObjectMapper().readTree(stream);
        }
    }

    @Test
    void everyOntologyRowIsCompleteIncludingKisaoOnOls4() {
        var ontologies = catalogue.ontologies();
        assertEquals(5, ontologies.size());
        for (var entry : ontologies) {
            assertTrue(entry.ontological(), entry.canonicalPrefix());
            assertTrue(entry.classIriTemplate().contains("{id}"), entry.canonicalPrefix());
            assertEquals(entry.canonicalPrefix(), entry.ols4Ontology());
        }
    }

    @Test
    void termKeyRecordWireFormAndValidation() {
        assertEquals("go:0008150", new TermKey("go", "0008150").wire());
        assertEquals(new TermKey("go", "0008150"), new TermKey("go", "0008150"));
        assertEquals(new TermKey("go", "0008150").hashCode(), new TermKey("go", "0008150").hashCode());
        assertFalse(new TermKey("go", "0008150").equals(new TermKey("go", "0008151")));
        assertFalse(new TermKey("go", "0008150").equals(new TermKey("chebi", "0008150")));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TermKey(null, "0008150"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TermKey("go", " "));
    }
}

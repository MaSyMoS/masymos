package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.semanticweb.owlapi.model.IRI;

class OwlSerialisationConverterTest {
    private static final URI SOURCE = URI.create("https://example.org/converted.owl");

    @Test
    void mapsEveryRequestedImportIriToALocalPlaceholderAndNeverToItself() throws Exception {
        var scratch = new ArrayList<java.nio.file.Path>();
        var mapper = OwlSerialisationConverter.importMapper(scratch);

        for (var requested : java.util.List.of(
                IRI.create("http://10.255.255.1/never-answers.owl"),
                IRI.create("file:/C:/nowhere/sibling-must-not-be-read.owl"))) {
            var document = mapper.getDocumentIRI(requested);

            assertNotEquals(requested, document, "an import must never resolve to itself");
            assertEquals("file", document.getScheme(), "an import must resolve to a local placeholder");
            assertTrue(scratch.stream().anyMatch(path -> document.toString().contains(path.getFileName().toString())),
                    "the placeholder must be one of the scratch files the converter deletes");
        }
    }

    @Test
    void recordsThatAnUnknownOwlXmlElementYieldsAnEmptyOntology() {
        // Measured 2026-11-16: OWLAPI's OWL/XML handler ignores an element it does not know and does
        // not throw when the document declares no axiom at all, so this is no longer a loud refusal.
        // The test pins the real behaviour instead of letting a reviewer discover it.
        var unknownElement = ("<Ontology xmlns=\"http://www.w3.org/2002/07/owl#\"><Nonsense/></Ontology>")
                .getBytes(StandardCharsets.UTF_8);

        var rdfXml = converter().toRdfXml(unknownElement, OwlSerialisation.OWL_XML, reference(SOURCE));

        var parsed = parse(rdfXml, SOURCE);
        assertEquals(1, parsed.terms().size(), "only the root term survives");
        assertTrue(parsed.relationships().isEmpty(), "no relationship survives");
    }

    @Test
    void convertsWithoutInventingADocumentBase() {
        // `base` is a document property, not an ontology fact: the RDF/XML path records the
        // document's xml:base because the DOM reader sees it, and a converted document has no
        // xml:base. OWLAPI does not surface Turtle's @base as a document prefix (measured
        // 2026-11-16: the format's default prefix is empty), and synthesising one would mean parsing
        // the directive once per syntax — the partial parsing this design rejects. So a converted
        // document carries no base, and owl-parity records that as the one graph property which is
        // not serialisation-invariant.
        var turtle = ("""
                @base <http://example.org/base/> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                <http://example.org/onto> a owl:Ontology .
                <#Entity> a owl:Class .
                """).getBytes(StandardCharsets.UTF_8);

        var rdfXml = converter().toRdfXml(turtle, OwlSerialisation.TURTLE, reference(SOURCE));

        var parsed = parse(rdfXml, SOURCE);
        assertNull(parsed.properties().get("base"), "a converted document must not invent a base");
        assertTrue(parsed.terms().stream().anyMatch(term -> term.id().equals("Entity")),
                "the @base must still resolve the relative class IRI");
    }

    @Test
    void convertsTurtleToRdfXmlTheExistingReaderAccepts() throws Exception {
        var turtle = resource("/owl/serialisation/sbo.ttl");

        var rdfXml = converter().toRdfXml(turtle, OwlSerialisation.TURTLE, reference(SOURCE));

        var converted = new String(rdfXml, StandardCharsets.UTF_8);
        assertTrue(converted.stripLeading().startsWith("<?xml"), converted.substring(0, 60));
        assertTrue(java.util.regex.Pattern.compile("(?s)<\\?xml[^>]*\\?>\\s*<rdf:RDF").matcher(converted).find(),
                "the emitted document must have an rdf:RDF root element");
        var parsed = parse(rdfXml, SOURCE);
        assertTrue(parsed.terms().stream().anyMatch(term -> term.id().equals("SBO_0000002")));
        assertTrue(parsed.relationships().stream().anyMatch(relationship -> relationship.type().equals("isA")));
    }

    @Test
    void namesTheSerialisationAndSourceUriWhenConversionFails() {
        var broken = "@prefix : <http://example.org/> .\n:broken a\n".getBytes(StandardCharsets.UTF_8);

        var failure = assertThrows(ModelParseException.class,
                () -> converter().toRdfXml(broken, OwlSerialisation.TURTLE, reference(SOURCE)));

        assertTrue(failure.getMessage().contains("Turtle"), failure.getMessage());
        assertTrue(failure.getMessage().contains(SOURCE.toString()), failure.getMessage());
    }

    @Test
    void namesOwlXmlWhenWellFormedXmlIsNotValidOwlXml() {
        // Well-formed XML with the OWL/XML root element (so detection names OWL/XML) but a
        // Declaration that carries no axiom: OWLAPI rejects it. An unknown element is NOT usable
        // here, because OWLAPI tolerates unknown elements and yields an empty ontology.
        var notOwlXml = ("<Ontology xmlns=\"http://www.w3.org/2002/07/owl#\"><Declaration/></Ontology>")
                .getBytes(StandardCharsets.UTF_8);

        var failure = assertThrows(ModelParseException.class,
                () -> converter().toRdfXml(notOwlXml, OwlSerialisation.OWL_XML, reference(SOURCE)));

        assertTrue(failure.getMessage().contains("OWL/XML"), failure.getMessage());
        assertTrue(failure.getMessage().contains(SOURCE.toString()), failure.getMessage());
    }

    @Test
    void namesFunctionalSyntaxWhenItCannotBeConverted() {
        var broken = ("Ontology(<http://example.org/o>\nThisIsNotAFunctionalAxiom(:x)\n)\n")
                .getBytes(StandardCharsets.UTF_8);

        var failure = assertThrows(ModelParseException.class,
                () -> converter().toRdfXml(broken, OwlSerialisation.FUNCTIONAL_SYNTAX, reference(SOURCE)));

        assertTrue(failure.getMessage().contains("OWL functional syntax"), failure.getMessage());
        assertTrue(failure.getMessage().contains(SOURCE.toString()), failure.getMessage());
    }

    @Test
    void neverFetchesAnImportAndNeverReadsASiblingFile() throws Exception {
        var documentUrl = resourceUrl("/owl/four-serialisations/imports.ttl");
        byte[] turtle;
        try (var stream = documentUrl.openStream()) {
            turtle = stream.readAllBytes();
        }
        var documentUri = documentUrl.toURI();

        var rdfXml = converter().toRdfXml(turtle, OwlSerialisation.TURTLE, reference(documentUri));

        var importResources = importResources(rdfXml);
        assertEquals(2, importResources.size(), "both import declarations must survive: " + importResources);
        assertTrue(importResources.contains("http://10.255.255.1/never-answers.owl"),
                "the absolute import keeps its IRI: " + importResources);
        assertEquals(1, importResources.stream().filter(iri -> iri.endsWith("sibling-must-not-be-read.owl"))
                        .count(),
                "the relative import is rewritten against the document: " + importResources);
        var parsed = parse(rdfXml, documentUri);
        assertTrue(parsed.terms().stream().anyMatch(term -> term.id().equals("LocalClass")));
        assertTrue(parsed.terms().stream().noneMatch(term -> term.id().contains("MarkerClass")),
                "a sibling document next to the source must never be read (the mapper test proves the mechanism)");
    }

    /** The {@code rdf:resource} values of the {@code owl:imports} triples in the converted document. */
    private java.util.List<String> importResources(byte[] rdfXml) {
        var matcher = java.util.regex.Pattern
                .compile("<owl:imports\\s+rdf:resource=\"([^\"]+)\"")
                .matcher(new String(rdfXml, StandardCharsets.UTF_8));
        var values = new java.util.ArrayList<String>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    @Test
    void convertsTheSameInputToAnEqualGraphTwice() throws Exception {
        var turtle = resource("/owl/serialisation/sbo.ttl");

        var first = parse(converter().toRdfXml(turtle, OwlSerialisation.TURTLE, reference(SOURCE)), SOURCE);
        var second = parse(converter().toRdfXml(turtle, OwlSerialisation.TURTLE, reference(SOURCE)), SOURCE);

        assertEquals(dump(first), dump(second));
    }

    @Test
    void displayNameIsTheHumanNameOfEachSerialisation() {
        assertEquals("RDF/XML", OwlSerialisation.RDF_XML.displayName());
        assertEquals("Turtle", OwlSerialisation.TURTLE.displayName());
        assertEquals("OWL/XML", OwlSerialisation.OWL_XML.displayName());
        assertEquals("OWL functional syntax", OwlSerialisation.FUNCTIONAL_SYNTAX.displayName());
        assertEquals("unrecognised", OwlSerialisation.UNRECOGNISED.displayName());
    }

    private OwlSerialisationConverter converter() {
        return new OwlSerialisationConverter();
    }

    private ModelReference reference(URI sourceUri) {
        return new ModelReference("converted.owl", sourceUri, ModelType.OWL);
    }

    private ParsedOntology parse(byte[] content, URI sourceUri) {
        return new OwlOntologyParser().parse(new ModelSource(reference(sourceUri)),
                new ByteArrayInputStream(content));
    }

    private byte[] resource(String classpathResource) throws Exception {
        try (var stream = getClass().getResourceAsStream(classpathResource)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + classpathResource);
            }
            return stream.readAllBytes();
        }
    }

    private URL resourceUrl(String classpathResource) {
        var url = getClass().getResource(classpathResource);
        if (url == null) {
            throw new IllegalStateException("missing test resource " + classpathResource);
        }
        return url;
    }

    private List<String> dump(ParsedOntology ontology) {
        var lines = new ArrayList<String>();
        lines.add("name=" + ontology.ontologyName());
        lines.add("label=" + ontology.ontologyLabel());
        lines.add("props=" + sortedProperties(ontology.properties()));
        ontology.terms().forEach(term ->
                lines.add("term " + term.id() + " " + sortedProperties(term.properties())));
        ontology.relationships().forEach(relationship ->
                lines.add("rel " + relationship.startId() + " " + relationship.type() + " "
                        + relationship.endId() + " " + sortedProperties(relationship.properties())));
        lines.sort(String::compareTo);
        return lines;
    }

    private TreeSet<String> sortedProperties(java.util.Map<String, String> properties) {
        var sorted = new TreeSet<String>();
        properties.forEach((key, value) -> sorted.add(key + "=" + value));
        return sorted;
    }
}

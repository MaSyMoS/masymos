package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;

class OwlOntologyParserTest {
    @Test
    void parsesLegacyRelevantClassHierarchy() {
        var source = source("sbo.owl");

        var parsed = new OwlOntologyParser().parse(source, stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:Ontology rdf:about="">
                    <rdfs:comment>Example ontology</rdfs:comment>
                    <owl:versionInfo>1</owl:versionInfo>
                  </owl:Ontology>
                  <owl:AnnotationProperty rdf:about="http://example.org/ontology#curatorNote">
                    <rdfs:label>curator note</rdfs:label>
                  </owl:AnnotationProperty>
                  <owl:ObjectProperty rdf:about="http://example.org/ontology#has_part">
                    <rdfs:label>has part</rdfs:label>
                    <rdfs:domain rdf:resource="SBO_0000001"/>
                    <rdfs:range rdf:resource="SBO_0000003"/>
                  </owl:ObjectProperty>
                  <owl:DatatypeProperty rdf:about="http://example.org/ontology#priority"/>
                  <owl:Class rdf:about="SBO_0000001">
                    <rdfs:label>entity</rdfs:label>
                  </owl:Class>
                  <owl:Class rdf:about="SBO_0000003">
                    <rdfs:label>part entity</rdfs:label>
                  </owl:Class>
                  <owl:Class rdf:about="SBO_0000002">
                    <rdfs:subClassOf>
                      <owl:Class rdf:about="SBO_0000001"/>
                    </rdfs:subClassOf>
                    <rdfs:subClassOf>
                      <owl:Restriction>
                        <owl:onProperty rdf:resource="http://example.org/ontology#has_part"/>
                        <owl:someValuesFrom rdf:resource="SBO_0000003"/>
                      </owl:Restriction>
                    </rdfs:subClassOf>
                    <rdfs:comment>child comment</rdfs:comment>
                    <rdfs:label>child entity</rdfs:label>
                  </owl:Class>
                  <owl:NamedIndividual rdf:about="individual_1">
                    <rdf:type rdf:resource="SBO_0000002"/>
                    <has_part rdf:resource="individual_2"/>
                    <priority>high</priority>
                  </owl:NamedIndividual>
                  <owl:NamedIndividual rdf:about="individual_2">
                    <rdf:type rdf:resource="SBO_0000003"/>
                  </owl:NamedIndividual>
                </rdf:RDF>
                """));

        assertEquals("SBOOntology", parsed.ontologyLabel());
        assertEquals("9", parsed.properties().get("ontology.terms"));
        assertEquals("7", parsed.properties().get("ontology.relationships"));
        assertEquals("1", parsed.properties().get("ontology.objectProperties"));
        assertEquals("1", parsed.properties().get("ontology.dataProperties"));
        assertEquals("1", parsed.properties().get("ontology.annotationProperties"));
        assertTrue(parsed.terms().stream().anyMatch(term ->
                term.id().equals("owl:SBOOntology") && term.properties().get("termType").equals("root")
        ));
        assertTrue(parsed.terms().stream().anyMatch(term ->
                term.id().equals("SBO_0000002") && term.properties().get("name").equals("child entity")
        ));
        assertTrue(parsed.terms().stream().anyMatch(term ->
                term.id().equals("has_part")
                        && term.properties().get("termType").equals("objectProperty")
                        && term.properties().get("domain").equals("SBO_0000001")
                        && term.properties().get("range").equals("SBO_0000003")
        ));
        assertTrue(parsed.terms().stream().anyMatch(term ->
                term.id().equals("curatorNote") && term.properties().get("termType").equals("annotationProperty")
        ));
        assertTrue(parsed.terms().stream().anyMatch(term ->
                term.id().equals("priority") && term.properties().get("termType").equals("dataProperty")
        ));
        assertTrue(parsed.terms().stream().anyMatch(term ->
                term.id().equals("individual_1")
                        && term.properties().get("termType").equals("individual")
                        && term.properties().get("priority").equals("high")
        ));
        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000001")
                        && relationship.type().equals("isA")
                        && relationship.endId().equals("owl:SBOOntology")
        ));
        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("isA")
                        && relationship.endId().equals("SBO_0000001")
        ));
        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("someValuesFrom")
                        && relationship.endId().equals("SBO_0000003")
                        && relationship.properties().get("onProperty").equals("has_part")
        ));
        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("individual_1")
                        && relationship.type().equals("isA")
                        && relationship.endId().equals("SBO_0000002")
        ));
        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("individual_1")
                        && relationship.type().equals("has_part")
                        && relationship.endId().equals("individual_2")
        ));
    }

    @Test
    void parsesAllValuesFromRestriction() {
        var parsed = new OwlOntologyParser().parse(source("allvalues.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:ObjectProperty rdf:about="http://example.org/ontology#partOf"/>
                  <owl:Class rdf:about="SBO_0000001"/>
                  <owl:Class rdf:about="SBO_0000002">
                    <rdfs:subClassOf>
                      <owl:Restriction>
                        <owl:onProperty rdf:resource="http://example.org/ontology#partOf"/>
                        <owl:allValuesFrom rdf:resource="SBO_0000001"/>
                      </owl:Restriction>
                    </rdfs:subClassOf>
                  </owl:Class>
                </rdf:RDF>
                """));

        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("allValuesFrom")
                        && relationship.endId().equals("SBO_0000001")
                        && relationship.properties().get("onProperty").equals("partOf")
        ));
    }

    @Test
    void parsesUnionOfRestriction() {
        var parsed = new OwlOntologyParser().parse(source("union.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:ObjectProperty rdf:about="http://example.org/ontology#has_part"/>
                  <owl:Class rdf:about="SBO_0000001"/>
                  <owl:Class rdf:about="SBO_0000003"/>
                  <owl:Class rdf:about="SBO_0000002">
                    <rdfs:subClassOf>
                      <owl:Restriction>
                        <owl:onProperty rdf:resource="http://example.org/ontology#has_part"/>
                        <owl:someValuesFrom>
                          <owl:Class>
                            <owl:unionOf rdf:parseType="Collection">
                              <rdf:Description rdf:about="SBO_0000001"/>
                              <rdf:Description rdf:about="SBO_0000003"/>
                            </owl:unionOf>
                          </owl:Class>
                        </owl:someValuesFrom>
                      </owl:Restriction>
                    </rdfs:subClassOf>
                  </owl:Class>
                </rdf:RDF>
                """));

        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("unionOf")
                        && relationship.endId().equals("SBO_0000001")
                        && relationship.properties().get("onProperty").equals("has_part")
        ));
        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("unionOf")
                        && relationship.endId().equals("SBO_0000003")
                        && relationship.properties().get("onProperty").equals("has_part")
        ));
    }

    @Test
    void parsesIntersectionOfRestriction() {
        var parsed = new OwlOntologyParser().parse(source("intersection.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:ObjectProperty rdf:about="http://example.org/ontology#has_part"/>
                  <owl:Class rdf:about="SBO_0000001"/>
                  <owl:Class rdf:about="SBO_0000003"/>
                  <owl:Class rdf:about="SBO_0000002">
                    <rdfs:subClassOf>
                      <owl:Restriction>
                        <owl:onProperty rdf:resource="http://example.org/ontology#has_part"/>
                        <owl:allValuesFrom>
                          <owl:Class>
                            <owl:intersectionOf rdf:parseType="Collection">
                              <rdf:Description rdf:about="SBO_0000001"/>
                              <rdf:Description rdf:about="SBO_0000003"/>
                            </owl:intersectionOf>
                          </owl:Class>
                        </owl:allValuesFrom>
                      </owl:Restriction>
                    </rdfs:subClassOf>
                  </owl:Class>
                </rdf:RDF>
                """));

        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("intersectionOf")
                        && relationship.endId().equals("SBO_0000001")
                        && relationship.properties().get("onProperty").equals("has_part")
        ));
        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("intersectionOf")
                        && relationship.endId().equals("SBO_0000003")
                        && relationship.properties().get("onProperty").equals("has_part")
        ));
    }

    @Test
    void derivesTheGenusOfAnIntersectionDefinition() {
        var parsed = new OwlOntologyParser().parse(source("definition-derived.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:ObjectProperty rdf:about="http://example.org/ontology#has_part"/>
                  <owl:Class rdf:about="SBO_0000001"/>
                  <owl:Class rdf:about="SBO_0000003"/>
                  <owl:Class rdf:about="SBO_0000002">
                    <owl:equivalentClass>
                      <owl:Class>
                        <owl:intersectionOf rdf:parseType="Collection">
                          <rdf:Description rdf:about="SBO_0000001"/>
                          <owl:Restriction>
                            <owl:onProperty rdf:resource="http://example.org/ontology#has_part"/>
                            <owl:someValuesFrom rdf:resource="SBO_0000003"/>
                          </owl:Restriction>
                        </owl:intersectionOf>
                      </owl:Class>
                    </owl:equivalentClass>
                  </owl:Class>
                </rdf:RDF>
                """));

        var genusEdges = parsed.relationships().stream()
                .filter(relationship -> relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("isA"))
                .toList();
        assertEquals(1, genusEdges.size(), "one named conjunct, and no root edge beside it");
        assertEquals("SBO_0000001", genusEdges.get(0).endId());
        assertEquals("equivalentClass", genusEdges.get(0).properties().get("derivedFrom"));
    }

    @Test
    void doesNotRepeatAGenusThatIsAlsoStated() {
        var parsed = new OwlOntologyParser().parse(source("definition-stated.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:Class rdf:about="SBO_0000001"/>
                  <owl:Class rdf:about="SBO_0000003"/>
                  <owl:Class rdf:about="SBO_0000002">
                    <rdfs:subClassOf rdf:resource="SBO_0000001"/>
                    <owl:equivalentClass>
                      <owl:Class>
                        <owl:intersectionOf rdf:parseType="Collection">
                          <rdf:Description rdf:about="SBO_0000001"/>
                          <rdf:Description rdf:about="SBO_0000003"/>
                        </owl:intersectionOf>
                      </owl:Class>
                    </owl:equivalentClass>
                  </owl:Class>
                </rdf:RDF>
                """));

        var stated = parsed.relationships().stream()
                .filter(relationship -> relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("isA")
                        && relationship.endId().equals("SBO_0000001"))
                .toList();
        assertEquals(1, stated.size(), "the stated edge is not repeated for the same pair");
        assertTrue(stated.get(0).properties().isEmpty(), "the stated edge carries no derivedFrom");

        var derived = parsed.relationships().stream()
                .filter(relationship -> relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("isA")
                        && "equivalentClass".equals(relationship.properties().get("derivedFrom")))
                .toList();
        assertEquals(1, derived.size(), "the other named conjunct is the one edge the definition adds");
        assertEquals("SBO_0000003", derived.get(0).endId());
    }

    @Test
    void doesNotTurnANamedEquivalenceIntoAnEdge() {
        var parsed = new OwlOntologyParser().parse(source("definition-equivalent.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:Class rdf:about="SBO_0000001">
                    <owl:equivalentClass rdf:resource="SBO_0000002"/>
                  </owl:Class>
                  <owl:Class rdf:about="SBO_0000002"/>
                </rdf:RDF>
                """));

        var namedPair = Set.of("SBO_0000001|SBO_0000002", "SBO_0000002|SBO_0000001");
        assertTrue(parsed.relationships().stream()
                .filter(relationship -> relationship.type().equals("isA"))
                .noneMatch(relationship -> namedPair.contains(
                        relationship.startId() + "|" + relationship.endId())),
                "an equivalence between two named classes is one node, not a subsumption edge");
    }

    @Test
    void doesNotReadAGenusOutOfAUnionDefinition() {
        var parsed = new OwlOntologyParser().parse(source("definition-union.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:Class rdf:about="SBO_0000001"/>
                  <owl:Class rdf:about="SBO_0000003"/>
                  <owl:Class rdf:about="SBO_0000002">
                    <owl:equivalentClass>
                      <owl:Class>
                        <owl:unionOf rdf:parseType="Collection">
                          <rdf:Description rdf:about="SBO_0000001"/>
                          <rdf:Description rdf:about="SBO_0000003"/>
                        </owl:unionOf>
                      </owl:Class>
                    </owl:equivalentClass>
                  </owl:Class>
                </rdf:RDF>
                """));

        var isa = parsed.relationships().stream()
                .filter(relationship -> relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("isA"))
                .toList();
        assertEquals(1, isa.size(), "a union states no superclass, so the root edge stays");
        assertTrue(isa.get(0).endId().startsWith("owl:"), "the kept edge is the one to the root term");
    }

    @Test
    void parsesComplementOfRestriction() {
        var parsed = new OwlOntologyParser().parse(source("complement.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:ObjectProperty rdf:about="http://example.org/ontology#has_part"/>
                  <owl:Class rdf:about="SBO_0000003"/>
                  <owl:Class rdf:about="SBO_0000002">
                    <rdfs:subClassOf>
                      <owl:Restriction>
                        <owl:onProperty rdf:resource="http://example.org/ontology#has_part"/>
                        <owl:someValuesFrom>
                          <owl:Class>
                            <owl:complementOf>
                              <owl:Class rdf:about="SBO_0000003"/>
                            </owl:complementOf>
                          </owl:Class>
                        </owl:someValuesFrom>
                      </owl:Restriction>
                    </rdfs:subClassOf>
                  </owl:Class>
                </rdf:RDF>
                """));

        assertTrue(parsed.relationships().stream().anyMatch(relationship ->
                relationship.startId().equals("SBO_0000002")
                        && relationship.type().equals("complementOf")
                        && relationship.endId().equals("SBO_0000003")
                        && relationship.properties().get("onProperty").equals("has_part")
        ));
    }

    @Test
    void parsesDataPropertyRange() {
        var parsed = new OwlOntologyParser().parse(source("datarange.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:DatatypeProperty rdf:about="http://example.org/ontology#priority">
                    <rdfs:domain rdf:resource="SBO_0000001"/>
                    <rdfs:range rdf:resource="http://www.w3.org/2001/XMLSchema#string"/>
                  </owl:DatatypeProperty>
                  <owl:Class rdf:about="SBO_0000001"/>
                </rdf:RDF>
                """));

        assertTrue(parsed.terms().stream().anyMatch(term ->
                term.id().equals("priority")
                        && term.properties().get("termType").equals("dataProperty")
                        && term.properties().get("domain").equals("SBO_0000001")
                        && term.properties().get("range").equals("string")
        ));
    }

    @Test
    void parsesAnImportedOntologyWithoutFetchingOrReadingASibling() throws Exception {
        var documentUrl = getClass().getResource("/owl/four-serialisations/imports.ttl");
        assertNotNull(documentUrl, "imports.ttl fixture");
        // The document IRI is the fixture's own location, so the relative import is genuinely
        // resolvable and "it was not read" is an assertion about behaviour, not about a dead URL.
        var source = new ModelSource(new ModelReference("imports.ttl", documentUrl.toURI(), ModelType.OWL));

        var parsed = new OwlOntologyParser().parse(source, documentUrl.openStream());

        assertTrue(parsed.terms().stream().anyMatch(term -> term.id().equals("LocalClass")),
                () -> "the document itself must be read");
        assertTrue(parsed.terms().stream().noneMatch(term -> term.id().contains("MarkerClass")),
                () -> "a sibling next to the document must never be read");
    }

    @Test
    void theCanonicalRdfXmlFixtureCarriesTheAxiomsTheOthersAreComparedTo() throws Exception {
        // Without this, all four fixtures derive from one sbo.ofn and one OWLAPI renderer, so an
        // axiom OWLAPI dropped on the way in would be missing from all four and the four-way
        // equality test would still pass. These assertions are the independent anchor.
        var parsed = parseFixture("sbo.rdf", "/owl/four-serialisations/sbo.rdf");

        assertEquals("SBOOntology", parsed.ontologyLabel());
        assertTrue(termIds(parsed).containsAll(Set.of(
                "SBO_0000001", "SBO_0000002", "SBO_0000003", "has_part", "priority", "curatorNote")),
                "terms: " + termIds(parsed));
        assertTrue(relationshipKeys(parsed).contains("SBO_0000002|isA|SBO_0000001"),
                "relationships: " + relationshipKeys(parsed));
        assertTrue(relationshipKeys(parsed).stream().anyMatch(key -> key.endsWith("|has_part")),
                "the someValuesFrom restriction must survive: " + relationshipKeys(parsed));
        assertEquals("child entity", termProperty(parsed, "SBO_0000002", "name"));
    }

    @Test
    void parsesAllFourSerialisationsToTheSameGraph() throws Exception {
        var fromRdfXml = parseFixture("sbo.rdf", "/owl/four-serialisations/sbo.rdf");

        for (var fixture : new String[] {"sbo.ttl", "sbo.owlxml", "sbo.ofn"}) {
            var parsed = parseFixture(fixture, "/owl/four-serialisations/" + fixture);

            assertEquals(dump(fromRdfXml), dump(parsed),
                    fixture + " must produce the same graph as sbo.rdf");
        }
    }

    @Test
    void rejectsUnknownContentWithTheUnknownFormatMessage() {
        var failure = assertThrows(ModelParseException.class,
                () -> parseFixture("notes.txt", "/owl/serialisation/notes.txt"));

        var message = failure.getMessage();
        assertTrue(message.contains("unrecognised OWL serialisation"), message);
        assertTrue(message.contains("rdf:RDF"), message);
    }

    @Test
    void sniffsContentIndependentOfFileExtension() throws Exception {
        // The Turtle fixture carries an unrelated file id: detection must not trust the name, and
        // the document must parse as the Turtle it is (OWL-2).
        var parsed = parseFixture("renamed.xml", "/owl/serialisation/sbo.ttl");

        assertTrue(parsed.terms().stream().anyMatch(term -> term.id().equals("SBO_0000002")));
    }

    @Test
    void parsesTurtleWithoutPrefixDeclarations() {
        var parsed = new OwlOntologyParser().parse(source("bare.ttl"), stream("""
                <http://biomodels.net/SBO/> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
                    <http://www.w3.org/2002/07/owl#Ontology> .
                <http://biomodels.net/SBO/Entity> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type>
                    <http://www.w3.org/2002/07/owl#Class> .
                """));

        assertTrue(parsed.terms().stream().anyMatch(term -> term.id().equals("Entity")));
    }

    @Test
    void parsesTurtleThatStartsWithAUtf8Bom() {
        var withBom = new java.io.ByteArrayOutputStream();
        withBom.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        withBom.writeBytes("""
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                <http://example.org/ontology> a owl:Ontology .
                <http://example.org/ontology#Entity> a owl:Class .
                """.getBytes(StandardCharsets.UTF_8));

        var parsed = new OwlOntologyParser().parse(source("bom.ttl"), stream(withBom.toByteArray()));

        assertTrue(parsed.terms().stream().anyMatch(term -> term.id().equals("Entity")));
    }

    @Test
    void namesTheSerialisationAndTheSourceUriWhenAConvertedDocumentIsBroken() {
        // The converter's message must reach the caller intact: it is the only thing that tells a
        // curator which syntax was detected and which document failed.
        var failure = assertThrows(ModelParseException.class, () -> new OwlOntologyParser().parse(
                source("broken.ttl"), stream("@prefix : <http://example.org/> .\n:broken a\n")));

        assertTrue(failure.getMessage().contains("Turtle"), failure.getMessage());
        assertTrue(failure.getMessage().contains("https://example.org/broken.ttl"), failure.getMessage());
    }

    @Test
    void detectsTheOwlXmlAndFunctionalSyntaxExamplesByContent() throws Exception {
        // Keeps the two examples in owl/serialisation/ live rather than decorative.
        OwlSerialisation owlXml;
        try (var in = getClass().getResourceAsStream("/owl/serialisation/sbo.owlxml")) {
            owlXml = OwlSerialisation.detect(Objects.requireNonNull(in).readAllBytes());
        }
        OwlSerialisation functional;
        try (var in = getClass().getResourceAsStream("/owl/serialisation/sbo.ofn")) {
            functional = OwlSerialisation.detect(Objects.requireNonNull(in).readAllBytes());
        }

        assertEquals(OwlSerialisation.OWL_XML, owlXml);
        assertEquals(OwlSerialisation.FUNCTIONAL_SYNTAX, functional);
    }

    @Test
    void rejectsRdfXmlThatFailsToParseAsXmlWithTheUnknownFormatMessage() {
        var failure = assertThrows(ModelParseException.class, () -> new OwlOntologyParser().parse(source("broken.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <owl:Class rdf:about="SBO_0000001">
                </rdf:RDF>
                """)));

        assertTrue(failure.getMessage().contains("unrecognised OWL serialisation"), failure.getMessage());
    }

    /**
     * Baseline for the serialisation-agnostic claim: this RDF/XML document declares the same content as
     * the Turtle, OWL/XML and functional-syntax fixtures, so the parsed term and relationship sets below
     * are the graph shape those three serialisations reproduce (see
     * {@link #parsesAllFourSerialisationsToTheSameGraph()}). Serialisation detection must not change the
     * RDF/XML result.
     */
    @Test
    void parsesRdfXmlUnchangedBySerialisationDetection() {
        var parsed = new OwlOntologyParser().parse(source("sbo.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns="http://biomodels.net/SBO/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
                    xmlns:xsd="http://www.w3.org/2001/XMLSchema#">
                  <owl:Ontology rdf:about="">
                    <rdfs:comment>Example ontology</rdfs:comment>
                    <owl:versionInfo>1</owl:versionInfo>
                  </owl:Ontology>
                  <owl:AnnotationProperty rdf:about="http://example.org/ontology#curatorNote"/>
                  <owl:ObjectProperty rdf:about="http://example.org/ontology#has_part">
                    <rdfs:domain rdf:resource="SBO_0000001"/>
                    <rdfs:range rdf:resource="SBO_0000003"/>
                  </owl:ObjectProperty>
                  <owl:DatatypeProperty rdf:about="http://example.org/ontology#priority">
                    <rdfs:range rdf:resource="http://www.w3.org/2001/XMLSchema#string"/>
                  </owl:DatatypeProperty>
                  <owl:Class rdf:about="SBO_0000001"/>
                  <owl:Class rdf:about="SBO_0000003"/>
                  <owl:Class rdf:about="SBO_0000002">
                    <rdfs:subClassOf>
                      <owl:Class rdf:about="SBO_0000001"/>
                    </rdfs:subClassOf>
                    <rdfs:subClassOf>
                      <owl:Restriction>
                        <owl:onProperty rdf:resource="http://example.org/ontology#has_part"/>
                        <owl:someValuesFrom rdf:resource="SBO_0000003"/>
                      </owl:Restriction>
                    </rdfs:subClassOf>
                  </owl:Class>
                  <owl:NamedIndividual rdf:about="individual_1">
                    <rdf:type rdf:resource="SBO_0000002"/>
                    <has_part rdf:resource="individual_2"/>
                  </owl:NamedIndividual>
                  <owl:NamedIndividual rdf:about="individual_2">
                    <rdf:type rdf:resource="SBO_0000003"/>
                  </owl:NamedIndividual>
                </rdf:RDF>
                """));

        assertEquals("SBOOntology", parsed.ontologyLabel());
        assertEquals("Example ontology", parsed.properties().get("ontology.comment"));
        assertEquals("1", parsed.properties().get("ontology.version"));
        assertEquals(Set.of(
                "owl:SBOOntology",
                "curatorNote",
                "has_part",
                "priority",
                "SBO_0000001",
                "SBO_0000002",
                "SBO_0000003",
                "individual_1",
                "individual_2"
        ), termIds(parsed));
        assertEquals(Set.of(
                "SBO_0000001|isA|owl:SBOOntology",
                "SBO_0000003|isA|owl:SBOOntology",
                "SBO_0000002|isA|SBO_0000001",
                "SBO_0000002|someValuesFrom|SBO_0000003|has_part",
                "individual_1|isA|SBO_0000002",
                "individual_2|isA|SBO_0000003",
                "individual_1|has_part|individual_2"
        ), relationshipKeys(parsed));
    }

    /**
     * OWL-3 regression: legacy named GO from what the ontology is, not from the file name. The file id
     * here deliberately says nothing about GO, so only the ontology IRI can produce the label.
     */
    @Test
    void namesGoOntologyFromTheOntologyIriNotTheFileName() {
        var parsed = new OwlOntologyParser().parse(source("model.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#"
                    xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#">
                  <owl:Ontology rdf:about="http://purl.obolibrary.org/obo/go.owl"/>
                  <owl:Class rdf:about="http://purl.obolibrary.org/obo/GO_0008150">
                    <rdfs:label>biological_process</rdfs:label>
                  </owl:Class>
                </rdf:RDF>
                """));

        assertEquals("GOOntology", parsed.ontologyLabel());
        assertEquals("GOOntology", termProperty(parsed, "owl:GOOntology", "ontology"));
        assertEquals("GOOntology", termProperty(parsed, "GO_0008150", "ontology"));
    }

    @Test
    void namesChebiOntologyWithTheLegacySpelling() {
        var parsed = new OwlOntologyParser().parse(source("model.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Ontology rdf:about="http://purl.obolibrary.org/obo/chebi.owl"/>
                  <owl:Class rdf:about="http://purl.obolibrary.org/obo/CHEBI_15377"/>
                </rdf:RDF>
                """));

        assertEquals("ChebiOntology", parsed.ontologyLabel());
        assertEquals("ChebiOntology", termProperty(parsed, "owl:ChebiOntology", "ontology"));
        assertEquals("ChebiOntology", termProperty(parsed, "CHEBI_15377", "ontology"));
    }

    @Test
    void namesSboAndKisaoOntologyFromTheirIris() {
        var sbo = new OwlOntologyParser().parse(source("model.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Ontology rdf:about="http://biomodels.net/SBO/SBO.owl"/>
                  <owl:Class rdf:about="SBO_0000001"/>
                </rdf:RDF>
                """));
        var kisao = new OwlOntologyParser().parse(source("model.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Ontology rdf:about="http://www.biomodels.net/kisao/KISAO_FULL#"/>
                  <owl:Class rdf:about="KISAO_0000001"/>
                </rdf:RDF>
                """));

        assertEquals("SBOOntology", sbo.ontologyLabel());
        assertEquals("SBOOntology", termProperty(sbo, "SBO_0000001", "ontology"));
        assertEquals("KISAOOntology", kisao.ontologyLabel());
        assertEquals("KISAOOntology", termProperty(kisao, "KISAO_0000001", "ontology"));
    }

    @Test
    void namesSboAndKisaoFromTheFileIdWhenNoOntologyIriIsStated() {
        var sbo = new OwlOntologyParser().parse(source("sbo.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Class rdf:about="SBO_0000001"/>
                </rdf:RDF>
                """));
        var kisao = new OwlOntologyParser().parse(source("kisao.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Ontology rdf:about=""/>
                  <owl:Class rdf:about="KISAO_0000001"/>
                </rdf:RDF>
                """));

        assertEquals("SBOOntology", sbo.ontologyLabel());
        assertEquals("SBOOntology", termProperty(sbo, "SBO_0000001", "ontology"));
        assertEquals("KISAOOntology", kisao.ontologyLabel());
        assertEquals("KISAOOntology", termProperty(kisao, "KISAO_0000001", "ontology"));
    }

    /**
     * Terminal-token matching only: a longer IRI segment that merely ends in "go" is not GO, so the
     * document falls back to the file id like any other unmatched ontology.
     */
    @Test
    void doesNotNameDecoyOntologyGo() {
        var parsed = new OwlOntologyParser().parse(source("model.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Ontology rdf:about="http://example.org/dgo.owl"/>
                  <owl:Class rdf:about="http://example.org/dgo#Thing"/>
                </rdf:RDF>
                """));

        assertEquals("modelOntology", parsed.ontologyLabel());
        assertEquals("modelOntology", termProperty(parsed, "owl:modelOntology", "ontology"));
        assertEquals("modelOntology", termProperty(parsed, "Thing", "ontology"));
    }

    @Test
    void namesUnknownOntologyFromTheFileNameWhenNoOntologyIriIsStated() {
        var parsed = new OwlOntologyParser().parse(source("family.owl"), stream("""
                <?xml version="1.0"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:owl="http://www.w3.org/2002/07/owl#">
                  <owl:Class rdf:about="http://example.org/family#Person"/>
                </rdf:RDF>
                """));

        assertEquals("familyOntology", parsed.ontologyLabel());
        assertEquals("familyOntology", termProperty(parsed, "owl:familyOntology", "ontology"));
        assertEquals("familyOntology", termProperty(parsed, "Person", "ontology"));
    }

    private static String termProperty(ParsedOntology ontology, String termId, String property) {
        return ontology.terms().stream()
                .filter(term -> term.id().equals(termId))
                .map(term -> term.properties().get(property))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no term " + termId + " in " + termIds(ontology)));
    }

    private static Set<String> termIds(ParsedOntology ontology) {
        return ontology.terms().stream().map(ParsedOntologyTerm::id).collect(Collectors.toSet());
    }

    private static Set<String> relationshipKeys(ParsedOntology ontology) {
        return ontology.relationships().stream()
                .map(relationship -> relationship.startId()
                        + "|" + relationship.type()
                        + "|" + relationship.endId()
                        + (relationship.properties().containsKey("onProperty")
                                ? "|" + relationship.properties().get("onProperty")
                                : ""))
                .collect(Collectors.toSet());
    }

    @Test
    void parsesProvidedOwlSamplesWhenAvailable() throws Exception {
        assertSample(Path.of("..", "..", "samples", "owl", "kisao.owl"), "KISAOOntology", 20);
        assertSample(Path.of("..", "..", "samples", "owl", "sbo.owl"), "SBOOntology", 675);
    }

    private void assertSample(Path sample, String ontologyLabel, int minimumTermCount) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(sample),
                sample + " is not in this checkout: the OWL sample corpus lives under samples/owl");
        try (var stream = Files.newInputStream(sample)) {
            var parsed = new OwlOntologyParser().parse(new ModelSource(new ModelReference(
                    sample.getFileName().toString(),
                    sample.toUri(),
                    ModelType.OWL
            )), stream);

            assertEquals(ontologyLabel, parsed.ontologyLabel());
            assertTrue(parsed.terms().size() >= minimumTermCount);
            assertTrue(parsed.relationships().stream().anyMatch(relationship -> relationship.type().equals("isA")));
        }
    }

    @Test
    void convertsTheRealOntologiesToTheGraphTheirRdfXmlProduces() throws Exception {
        // The counts are the ones decision 18 quotes, so the document's evidence is executable.
        assertRealOntologyConverts("sbo.owl", 697, 747);
        assertRealOntologyConverts("kisao.owl", 551, 1109);
    }

    private void assertRealOntologyConverts(String fileName, int expectedTerms, int expectedRelationships)
            throws Exception {
        var sample = Path.of("..", "..", "samples", "owl", fileName);
        Assumptions.assumeTrue(Files.isRegularFile(sample), fileName + " is not in this checkout");

        ParsedOntology direct;
        try (var stream = Files.newInputStream(sample)) {
            direct = new OwlOntologyParser().parse(new ModelSource(new ModelReference(
                    sample.getFileName().toString(),
                    sample.toUri(),
                    ModelType.OWL
            )), stream);
        }

        var asTurtle = expressAsTurtle(sample);
        var viaConverter = new OwlOntologyParser().parse(
                new ModelSource(new ModelReference(
                        fileName.replace(".owl", ".ttl"),
                        URI.create("https://example.org/" + fileName.replace(".owl", ".ttl")),
                        ModelType.OWL
                )),
                new ByteArrayInputStream(asTurtle));

        assertEquals(expectedTerms, direct.terms().size(), fileName + " term count");
        assertEquals(expectedRelationships, direct.relationships().size(), fileName + " relationship count");
        assertEquals(dump(direct), dump(viaConverter), fileName + " must convert to the same graph");
    }

    /**
     * Re-expresses a real RDF/XML ontology as Turtle with OWLAPI, so the new path is exercised on a
     * document nobody wrote for a test.
     */
    private byte[] expressAsTurtle(Path sample) throws Exception {
        var manager = OWLManager.createOWLOntologyManager();
        var ontology = manager.loadOntologyFromOntologyDocument(sample.toFile());
        var turtle = new ByteArrayOutputStream();
        manager.saveOntology(ontology, new TurtleDocumentFormat(), turtle);
        return turtle.toByteArray();
    }

    private ModelSource source(String fileId) {
        return new ModelSource(new ModelReference(fileId, URI.create("https://example.org/" + fileId), ModelType.OWL));
    }

    private ParsedOntology parseFixture(String fileId, String classpathResource) throws Exception {
        try (var stream = getClass().getResourceAsStream(classpathResource)) {
            assertNotNull(stream, classpathResource);
            return new OwlOntologyParser().parse(source(fileId), stream);
        }
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private ByteArrayInputStream stream(byte[] content) {
        return new ByteArrayInputStream(content);
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

    private TreeSet<String> sortedProperties(Map<String, String> properties) {
        var sorted = new TreeSet<String>();
        properties.forEach((key, value) -> sorted.add(key + "=" + value));
        return sorted;
    }
}

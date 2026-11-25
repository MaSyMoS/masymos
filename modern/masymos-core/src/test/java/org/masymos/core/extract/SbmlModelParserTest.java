package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class SbmlModelParserTest {
    private static final String SBML_CORE = "http://www.sbml.org/sbml/level3/version1/core";

    private ModelSource source;

    @BeforeEach
    void createSource() {
        source = new ModelSource(new ModelReference(
                "test.xml",
                URI.create("https://example.org/test.xml"),
                ModelType.SBML
        ));
    }

    @Test
    void parsesCoreSbmlMetadata() {
        var parsed = new SbmlModelParser().parse(source, stream(sbml()));

        assertEquals("demo_model", parsed.modelId());
        assertEquals("Demo model", parsed.modelName());
        assertEquals("3", parsed.properties().get("sbml.level"));
        assertEquals("2", parsed.properties().get("sbml.version"));
        assertEquals("1", parsed.properties().get("sbml.compartments"));
        assertEquals("2", parsed.properties().get("sbml.species"));
        assertEquals("1", parsed.properties().get("sbml.reactions"));
        assertEquals("1", parsed.properties().get("sbml.parameters"));
    }

    @Test
    void parsesLegacyRelevantSbmlEntitiesAndReactionParticipants() {
        var parsed = new SbmlModelParser().parse(source, stream(sbml()));

        assertEquals(5, parsed.entities().size());
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.COMPARTMENT
                        && entity.id().equals("cell")
                        && entity.name().equals("Cell")
        ));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SPECIES
                        && entity.id().equals("s1")
                        && entity.properties().get("compartment").equals("cell")
        ));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.REACTION
                        && entity.id().equals("r1")
                        && entity.name().equals("conversion")
        ));
        assertEquals(2, parsed.reactionParticipants().size());
        assertTrue(parsed.reactionParticipants().stream().anyMatch(participant ->
                participant.reactionId().equals("r1")
                        && participant.speciesId().equals("s1")
                        && participant.role() == ParsedReactionParticipant.Role.REACTANT
        ));
        assertTrue(parsed.reactionParticipants().stream().anyMatch(participant ->
                participant.reactionId().equals("r1")
                        && participant.speciesId().equals("s2")
                        && participant.role() == ParsedReactionParticipant.Role.PRODUCT
        ));
    }

    @Test
    void parsesLegacyRelevantSbmlAnnotationsCreatorsAndSboTerms() {
        var parsed = new SbmlModelParser().parse(source, stream(annotatedSbml()));

        assertEquals(2, parsed.annotations().size());
        assertTrue(parsed.annotations().stream().anyMatch(annotation ->
                annotation.subjectKey().equals("model")
                        && annotation.properties().containsKey("created")
                        && annotation.creators().stream().anyMatch(creator ->
                                creator.givenName().equals("Jane")
                                        && creator.familyName().equals("Doe")
                                        && creator.email().equals("jane@example.org")
                        )
                        && annotation.resources().stream().anyMatch(resource ->
                                resource.uri().equals("https://identifiers.org/go/GO:0008150")
                                        && resource.qualifier().equals("is")
                        )
        ));
        assertTrue(parsed.annotations().stream().anyMatch(annotation ->
                annotation.subjectKey().equals("species:s1")
                        && annotation.sboTerms().contains("urn:miriam:biomodels.sbo:SBO:0000247")
        ));
    }

    @Test
    void parsesLegacyRelevantRuleAnnotationsAndSboTerms() {
        var parsed = new SbmlModelParser().parse(source, stream(annotatedRuleSbml()));

        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.RULE
                        && entity.id().equals("_rule1")
        ));
        assertTrue(parsed.annotations().stream().anyMatch(annotation ->
                annotation.subjectKey().equals("rule:_rule1")
                        && annotation.resources().stream().anyMatch(resource ->
                                resource.uri().equals("https://identifiers.org/go/GO:0008150")
                                        && resource.qualifier().equals("isVersionOf")
                        )
                        && annotation.sboTerms().contains("urn:miriam:biomodels.sbo:SBO:0000064")
        ));
    }

    @Test
    void parsesProvidedSampleSbmlModelWhenSamplesAreAvailable() throws Exception {
        var sample = Path.of("..", "..", "samples", "sbml", "BIOMD0000000486.xml");
        Assumptions.assumeTrue(Files.isRegularFile(sample),
                sample.toAbsolutePath() + " is not in this checkout: the SBML sample lives under samples/sbml");

        try (var stream = Files.newInputStream(sample)) {
            var parsed = new SbmlModelParser().parse(new ModelSource(new ModelReference(
                    sample.getFileName().toString(),
                    sample.toUri(),
                    ModelType.SBML
            )), stream);

            assertTrue(parsed.entities().size() > 0);
        }
    }

    @Test
    @Timeout(30)
    void parsesProvidedSampleSbmlCorpusWhenSamplesAreAvailable() throws Exception {
        var sampleDirectory = Path.of("..", "..", "samples", "sbml");
        Assumptions.assumeTrue(Files.isDirectory(sampleDirectory),
                sampleDirectory.toAbsolutePath() + " is not in this checkout: the SBML corpus lives under samples/sbml");

        try (Stream<Path> samples = Files.list(sampleDirectory)) {
            var files = samples
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();
            Assumptions.assumeFalse(files.isEmpty(),
                    sampleDirectory.toAbsolutePath() + " holds no *.xml: the SBML corpus lives under samples/sbml");

            for (Path sample : files) {
                try (var stream = Files.newInputStream(sample)) {
                    var parsed = new SbmlModelParser().parse(new ModelSource(new ModelReference(
                            sample.getFileName().toString(),
                            sample.toUri(),
                            ModelType.SBML
                    )), stream);

                    assertTrue(
                            parsed.entities().size() > 0,
                            "expected parsed entities for " + sample.getFileName()
                    );
                }
            }
        }
    }

    private String sbml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version2/core" level="3" version="2">
                  <model id="demo_model" name="Demo model">
                    <listOfCompartments>
                      <compartment id="cell" name="Cell" constant="true"/>
                    </listOfCompartments>
                    <listOfSpecies>
                      <species id="s1" name="Substrate" compartment="cell" hasOnlySubstanceUnits="false"
                          boundaryCondition="false" constant="false"/>
                      <species id="s2" name="Product" compartment="cell" hasOnlySubstanceUnits="false"
                          boundaryCondition="false" constant="false"/>
                    </listOfSpecies>
                    <listOfParameters>
                      <parameter id="k1" name="Rate" constant="true"/>
                    </listOfParameters>
                    <listOfReactions>
                      <reaction id="r1" name="conversion" reversible="false" fast="false">
                        <listOfReactants>
                          <speciesReference species="s1" constant="true"/>
                        </listOfReactants>
                        <listOfProducts>
                          <speciesReference species="s2" constant="true"/>
                        </listOfProducts>
                      </reaction>
                    </listOfReactions>
                  </model>
                </sbml>
                """;
    }

    @Test
    void parsesCompartmentSpatialDimensionsWhenSet() {
        var parsed = new SbmlModelParser().parse(source, stream(spatialDimensionsSbml()));

        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.COMPARTMENT
                        && entity.id().equals("c")
                        && entity.properties().get("size").equals("1.0")
                        && entity.properties().get("spatialDimensions").equals("3")
        ));
    }

    @Test
    void exposesSpeciesReferenceStoichiometryOnParticipant() {
        var parsed = new SbmlModelParser().parse(source, stream(stoichiometrySbml()));

        assertTrue(parsed.reactionParticipants().stream().anyMatch(participant ->
                participant.reactionId().equals("r1")
                        && participant.speciesId().equals("a")
                        && participant.role() == ParsedReactionParticipant.Role.REACTANT
                        && participant.properties().get("stoichiometry").equals("2")
        ));
        assertTrue(parsed.entities().stream().noneMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.REACTION
                        && entity.id().equals("r1")
                        && entity.properties().keySet().stream().anyMatch(key -> key.startsWith("stoichiometry"))
        ));
    }

    @Test
    void keepsReactantAndProductStoichiometryWhenSameSpeciesIsOnBothSides() {
        var parsed = new SbmlModelParser().parse(source, stream(sameSpeciesBothSidesSbml()));

        var stoichiometries = parsed.reactionParticipants().stream()
                .filter(participant -> participant.reactionId().equals("r1"))
                .filter(participant -> participant.speciesId().equals("a"))
                .collect(Collectors.toMap(
                        ParsedReactionParticipant::role,
                        participant -> participant.properties().get("stoichiometry")
                ));

        assertEquals(Map.of(
                ParsedReactionParticipant.Role.REACTANT, "2",
                ParsedReactionParticipant.Role.PRODUCT, "3"
        ), stoichiometries);
    }

    private String spatialDimensionsSbml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level2/version4" level="2" version="4">
                  <model id="dimension_model">
                    <listOfCompartments>
                      <compartment id="c" size="1" spatialDimensions="3"/>
                    </listOfCompartments>
                  </model>
                </sbml>
                """;
    }

    private String stoichiometrySbml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level2/version4" level="2" version="4">
                  <model id="stoichiometry_model">
                    <listOfCompartments>
                      <compartment id="c" size="1" spatialDimensions="3"/>
                    </listOfCompartments>
                    <listOfSpecies>
                      <species id="a" compartment="c" initialConcentration="1"/>
                      <species id="b" compartment="c" initialConcentration="0"/>
                    </listOfSpecies>
                    <listOfReactions>
                      <reaction id="r1" reversible="false">
                        <listOfReactants>
                          <speciesReference species="a" stoichiometry="2"/>
                        </listOfReactants>
                        <listOfProducts>
                          <speciesReference species="b"/>
                        </listOfProducts>
                      </reaction>
                    </listOfReactions>
                  </model>
                </sbml>
                """;
    }

    private String sameSpeciesBothSidesSbml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level2/version4" level="2" version="4">
                  <model id="same_species_model">
                    <listOfCompartments>
                      <compartment id="c" size="1" spatialDimensions="3"/>
                    </listOfCompartments>
                    <listOfSpecies>
                      <species id="a" compartment="c" initialConcentration="1"/>
                    </listOfSpecies>
                    <listOfReactions>
                      <reaction id="r1" reversible="true">
                        <listOfReactants>
                          <speciesReference species="a" stoichiometry="2"/>
                        </listOfReactants>
                        <listOfProducts>
                          <speciesReference species="a" stoichiometry="3"/>
                        </listOfProducts>
                      </reaction>
                    </listOfReactions>
                  </model>
                </sbml>
                """;
    }

    @Test
    void ignoresAnAnnotationThatIsNotRdf() {
        // Measured on BioModels BIOMD0000001081 (CORP-4): JSBML casts a non-RDF <annotation> to
        // Annotation and throws ClassCastException. An unreadable annotation must not cost the document.
        var sbml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level2/version4" level="2" version="4">
                  <model id="m">
                    <annotation><frog xmlns="https://example.org/frog"><value>1</value></frog></annotation>
                    <listOfCompartments><compartment id="c" size="1"/></listOfCompartments>
                  </model>
                </sbml>
                """;

        var parsed = new SbmlModelParser().parse(source, stream(sbml));

        assertTrue(parsed.entities().stream()
                .anyMatch(entity -> entity.type() == ParsedModelEntity.EntityType.COMPARTMENT));
        assertTrue(parsed.annotations().isEmpty(), "a non-RDF annotation is not an annotation");
    }

    @Test
    void rejectsAMalformedDocument() {
        var failure = assertThrows(ModelParseException.class, () ->
                new SbmlModelParser().parse(source, stream("<sbml xmlns=\"" + SBML_CORE + "\"><model id=\"m\">")));

        assertTrue(
                failure.getMessage().contains("https://example.org/test.xml"),
                "the message names the source URI: " + failure.getMessage()
        );
    }

    @Test
    void rejectsAWrongNamespaceRoot() {
        var foreign = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://probe.invalid/sbml" level="3" version="1"><model id="m"/></sbml>
                """;

        var failure = assertThrows(ModelParseException.class, () ->
                new SbmlModelParser().parse(source, stream(foreign)));

        assertTrue(
                failure.getMessage().contains("https://example.org/test.xml"),
                "the message names the source URI: " + failure.getMessage()
        );
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private String annotatedSbml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version2/core" level="3" version="2"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:dcterms="http://purl.org/dc/terms/"
                    xmlns:vCard="http://www.w3.org/2001/vcard-rdf/3.0#"
                    xmlns:bqbiol="http://biomodels.net/biology-qualifiers/">
                  <model id="annotated_model" name="Annotated model" metaid="_annotated_model">
                    <annotation>
                      <rdf:RDF>
                        <rdf:Description rdf:about="#_annotated_model">
                          <dcterms:created rdf:parseType="Resource">
                            <dcterms:W3CDTF>2024-01-01T00:00:00Z</dcterms:W3CDTF>
                          </dcterms:created>
                          <dc:creator>
                            <rdf:Bag>
                              <rdf:li rdf:parseType="Resource">
                                <vCard:N rdf:parseType="Resource">
                                  <vCard:Family>Doe</vCard:Family>
                                  <vCard:Given>Jane</vCard:Given>
                                </vCard:N>
                                <vCard:EMAIL>jane@example.org</vCard:EMAIL>
                                <vCard:ORG rdf:parseType="Resource">
                                  <vCard:Orgname>Example Institute</vCard:Orgname>
                                </vCard:ORG>
                              </rdf:li>
                            </rdf:Bag>
                          </dc:creator>
                          <bqbiol:is>
                            <rdf:Bag>
                              <rdf:li rdf:resource="https://identifiers.org/go/GO:0008150"/>
                            </rdf:Bag>
                          </bqbiol:is>
                        </rdf:Description>
                      </rdf:RDF>
                    </annotation>
                    <listOfCompartments>
                      <compartment id="cell" constant="true"/>
                    </listOfCompartments>
                    <listOfSpecies>
                      <species id="s1" compartment="cell" hasOnlySubstanceUnits="false"
                          boundaryCondition="false" constant="false" sboTerm="SBO:0000247"/>
                    </listOfSpecies>
                  </model>
                </sbml>
                """;
    }

    private String annotatedRuleSbml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version2/core" level="3" version="2"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:bqbiol="http://biomodels.net/biology-qualifiers/">
                  <model id="rule_model" name="Rule model">
                    <listOfParameters>
                      <parameter id="k1" constant="false"/>
                    </listOfParameters>
                    <listOfRules>
                      <assignmentRule variable="k1" metaid="_rule1" sboTerm="SBO:0000064">
                        <math xmlns="http://www.w3.org/1998/Math/MathML">
                          <cn>1</cn>
                        </math>
                        <annotation>
                          <rdf:RDF>
                            <rdf:Description rdf:about="#_rule1">
                              <bqbiol:isVersionOf>
                                <rdf:Bag>
                                  <rdf:li rdf:resource="https://identifiers.org/go/GO:0008150"/>
                                </rdf:Bag>
                              </bqbiol:isVersionOf>
                            </rdf:Description>
                          </rdf:RDF>
                        </annotation>
                      </assignmentRule>
                    </listOfRules>
                  </model>
                </sbml>
                """;
    }
}

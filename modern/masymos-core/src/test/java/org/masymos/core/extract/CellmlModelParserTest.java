package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class CellmlModelParserTest {
    private ModelSource source;

    @BeforeEach
    void createSource() {
        source = new ModelSource(new ModelReference(
                "test.cellml",
                URI.create("https://example.org/test.cellml"),
                ModelType.CELLML
        ));
    }

    @Test
    void parsesLegacyRelevantCellmlModelComponentsAndVariables() {
        var parsed = new CellmlModelParser().parse(source, stream(cellml()));

        assertEquals("model_meta", parsed.modelId());
        assertEquals("demo_cellml", parsed.modelName());
        assertEquals("2", parsed.properties().get("cellml.components"));
        assertEquals("7", parsed.properties().get("cellml.variables"));
        assertEquals("1", parsed.properties().get("cellml.reactions"));
        assertEquals("1", parsed.properties().get("cellml.connections"));
        assertEquals("2", parsed.properties().get("cellml.annotations"));
        assertEquals("1", parsed.properties().get("cellml.publications"));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.COMPONENT
                        && entity.id().equals("environment_meta")
                        && entity.name().equals("environment")
        ));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.VARIABLE
                        && entity.id().equals("environment/time")
                        && entity.name().equals("time")
                        && entity.properties().get("component").equals("environment")
        ));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.CELLML_REACTION
                        && entity.id().equals("calcium/reaction-0")
                        && entity.properties().get("component").equals("calcium")
                        && entity.properties().get("reversible").equals("true")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("VARIABLE:environment/time")
                        && relationship.type().equals("IS_CONNECTED_TO")
                        && relationship.endEntityKey().equals("VARIABLE:ca_i_meta")
                        && relationship.properties().get("isPrivateConnection").equals("true")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("COMPONENT:calcium")
                        && relationship.type().equals("HAS_REACTION")
                        && relationship.endEntityKey().equals("CELLML_REACTION:calcium/reaction-0")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("CELLML_REACTION:calcium/reaction-0")
                        && relationship.type().equals("HAS_REACTANT")
                        && relationship.endEntityKey().equals("VARIABLE:substrate_meta")
                        && relationship.properties().get("role").equals("reactant")
                        && relationship.properties().get("direction").equals("forward")
                        && relationship.properties().get("stoichiometry").equals("2")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("CELLML_REACTION:calcium/reaction-0")
                        && relationship.type().equals("HAS_PRODUCT")
                        && relationship.endEntityKey().equals("VARIABLE:product_meta")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("CELLML_REACTION:calcium/reaction-0")
                        && relationship.type().equals("HAS_MODIFIER")
                        && relationship.endEntityKey().equals("VARIABLE:enzyme_meta")
                        && relationship.properties().get("role").equals("catalyst")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("CELLML_REACTION:calcium/reaction-0")
                        && relationship.type().equals("HAS_DELTA_VAR")
                        && relationship.endEntityKey().equals("VARIABLE:rate_meta")
                        && relationship.properties().get("deltaVariable").equals("reaction_rate")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("VARIABLE:product_meta")
                        && relationship.type().equals("IS_PRODUCT")
                        && relationship.endEntityKey().equals("CELLML_REACTION:calcium/reaction-0")
        ));
        assertTrue(parsed.annotations().stream().anyMatch(annotation ->
                annotation.subjectKey().equals("model")
                        && annotation.properties().get("title").equals("Synthetic CellML metadata model")
                        && annotation.resources().stream().anyMatch(resource ->
                                resource.uri().equals("https://identifiers.org/pubmed/12345")
                                        && resource.qualifier().equals("isDescribedBy"))
                        && annotation.creators().stream().anyMatch(creator ->
                                creator.givenName().equals("Ada")
                                        && creator.familyName().equals("Lovelace")
                                        && creator.email().equals("ada@example.org")
                                        && creator.organization().equals("Analytical Engine Group; Example Institute"))
        ));
        assertTrue(parsed.annotations().stream().anyMatch(annotation ->
                annotation.subjectKey().equals("COMPONENT:environment_meta")
                        && annotation.properties().get("comment").equals("Environment component metadata")
        ));
        assertTrue(parsed.publications().stream().anyMatch(publication ->
                publication.pubId().equals("https://identifiers.org/pubmed/12345")
                        && publication.title().equals("Synthetic article title")
                        && publication.journal().equals("Journal of Synthetic Models")
                        && publication.year().equals("2024")
                        && publication.authors().stream().anyMatch(author ->
                                author.givenName().equals("Grace") && author.familyName().equals("Hopper"))
        ));
    }

    @Test
    void parsesDownloadedCellmlSamplesWhenAvailable() throws Exception {
        var samples = List.of(
                Path.of("..", "..", "samples", "cellml", "downloaded", "schmid_nash_young_hunter_2006_a.cellml"),
                Path.of("..", "..", "samples", "cellml", "downloaded", "schmid_nash_young_hunter_2006_b.cellml")
        );

        for (Path sample : samples) {
            Assumptions.assumeTrue(Files.isRegularFile(sample));
            try (var stream = Files.newInputStream(sample)) {
                var parsed = new CellmlModelParser().parse(new ModelSource(new ModelReference(
                        sample.getFileName().toString(),
                        sample.toUri(),
                        ModelType.CELLML
                )), stream);

                assertTrue(parsed.entities().stream().anyMatch(entity ->
                        entity.type() == ParsedModelEntity.EntityType.COMPONENT
                ));
                assertTrue(parsed.entities().stream().anyMatch(entity ->
                        entity.type() == ParsedModelEntity.EntityType.VARIABLE
                ));
                assertTrue(parsed.annotations().stream().anyMatch(annotation ->
                        annotation.creators().stream().anyMatch(creator ->
                                creator.familyName().equals("Schmid"))
                ));
                assertTrue(parsed.publications().stream().anyMatch(publication ->
                        publication.title().contains("Myocardial Material Parameter Estimation")
                                && publication.pubId().equals("https://identifiers.org/pubmed/16995761"))
                );
            }
        }
    }

    @Test
    void mapsExternalPmrCmetaToModelAnnotationAndPublication() {
        var cmeta = new CellmlExternalCmeta(
                "External Model Group",
                List.of(),
                "External citation title",
                List.of(List.of("Curie", "Marie"), List.of("Franklin", "Rosalind")),
                "External model title",
                "Journal of External Metadata",
                "Alan Turing",
                "https://identifiers.org/pubmed/98765"
        );
        var parsed = new CellmlModelParser(source -> List.of(cmeta)).parse(source, stream(minimalCellml()));

        assertEquals("1", parsed.properties().get("cellml.annotations"));
        assertEquals("1", parsed.properties().get("cellml.publications"));
        assertTrue(parsed.annotations().stream().anyMatch(annotation ->
                annotation.subjectKey().equals("model")
                        && annotation.properties().get("title").equals("External model title")
                        && annotation.resources().stream().anyMatch(resource ->
                                resource.uri().equals("https://identifiers.org/pubmed/98765")
                                        && resource.qualifier().equals("isDescribedBy"))
                        && annotation.creators().stream().anyMatch(creator ->
                                creator.givenName().equals("Alan")
                                        && creator.familyName().equals("Turing")
                                        && creator.organization().equals("External Model Group"))
        ));
        assertTrue(parsed.publications().stream().anyMatch(publication ->
                publication.pubId().equals("https://identifiers.org/pubmed/98765")
                        && publication.title().equals("External citation title")
                        && publication.journal().equals("Journal of External Metadata")
                        && publication.authors().stream().anyMatch(author ->
                                author.givenName().equals("Marie") && author.familyName().equals("Curie"))
        ));
    }

    @Test
    void recordsCellmlVersionAndNamespaceFromDocumentRoot() {
        var parsed = new CellmlModelParser().parse(source, stream(cellml20()));

        assertEquals("2.0", parsed.properties().get("cellml.version"));
        assertTrue(parsed.properties().get("cellml.namespace").contains("http://www.cellml.org/cellml/2.0#"));
    }

    @Test
    void recordsUnitsOnCellmlVariables() {
        var parsed = new CellmlModelParser().parse(source, stream(cellml20()));

        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.VARIABLE
                        && entity.id().equals("environment/time")
                        && entity.properties().get("units").equals("second")
        ));
    }

    @Test
    void modelsImportedComponentsAsImportedComponentEntities() {
        // CML-2 moved imported components off COMPONENT: a distinct type is what stops an imported
        // component merging with a local component of the same name in ParsedModelGraphMapper.
        var parsed = new CellmlModelParser().parse(source, stream(cellml20WithImports()));

        var imports = parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.CELLML_IMPORT)
                .toList();
        assertEquals(1, imports.size());
        assertEquals("other.cellml", imports.getFirst().properties().get("href"));
        assertEquals("false", imports.getFirst().properties().get("resolved"));
        assertEquals("no-resolver", imports.getFirst().properties().get("resolution"));

        var imported = parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.CELLML_IMPORTED_COMPONENT)
                .toList();
        assertEquals(2, imported.size());
        assertTrue(imported.stream().allMatch(entity -> "true".equals(entity.properties().get("imported"))));
        assertTrue(imported.stream().allMatch(entity -> "other.cellml".equals(entity.properties().get("importedFrom"))));
        assertTrue(imported.stream().allMatch(entity -> "false".equals(entity.properties().get("resolved"))));
        assertTrue(imported.stream().anyMatch(entity ->
                entity.id().equals("other.cellml#comp_in_other_file") && entity.name().equals("comp_in_other_file")));
        assertTrue(imported.stream().anyMatch(entity -> entity.id().equals("other.cellml#another_imported_component")));
        assertTrue(parsed.entities().stream().noneMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.COMPONENT
                        && "true".equals(entity.properties().get("imported"))
        ));
        assertTrue(parsed.entities().stream().noneMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.VARIABLE
                        && "comp_in_other_file".equals(entity.properties().get("component"))
        ));
    }

    @Test
    void recordsUnitsDefinitionsDeclaredByTheModel() {
        var parsed = new CellmlModelParser().parse(source, stream(cellml20WithUnits()));

        assertEquals("2", parsed.properties().get("cellml.units"));
    }

    @Test
    void connectsCellml20ComponentsUsingConnectionAttributes() {
        var parsed = new CellmlModelParser().parse(source, stream(cellml20WithInlineConnection()));

        assertEquals("2.0", parsed.properties().get("cellml.version"));
        assertEquals("1", parsed.properties().get("cellml.connections"));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("VARIABLE:a/time")
                        && relationship.type().equals("IS_CONNECTED_TO")
                        && relationship.endEntityKey().equals("VARIABLE:b/value")
                        && relationship.properties().get("component1").equals("a")
                        && relationship.properties().get("component2").equals("b")
        ));
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private String minimalCellml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.1#"
                    xmlns:cmeta="http://www.cellml.org/metadata/1.0#"
                    name="minimal_cellml"
                    cmeta:id="minimal_model"/>
                """;
    }

    private String cellml20() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/2.0#"
                    name="demo"
                    version="2.0">
                  <component name="environment">
                    <variable name="time" units="second"/>
                  </component>
                </model>
                """;
    }

    private String cellml20WithUnits() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/2.0#"
                    name="demo_units"
                    version="2.0">
                  <units name="per_second">
                    <unit prefix="-" units="second" exponent="-1"/>
                  </units>
                  <units name="millimolar">
                    <unit prefix="milli" units="mole"/>
                  </units>
                  <component name="environment">
                    <variable name="time" units="second"/>
                  </component>
                </model>
                """;
    }

    private String cellml20WithInlineConnection() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/2.0#"
                    name="demo_connection"
                    version="2.0">
                  <component name="a">
                    <variable name="time" units="second"/>
                  </component>
                  <component name="b">
                    <variable name="value" units="second" private_interface="in"/>
                  </component>
                  <connection component_1="a" component_2="b">
                    <map_variables variable_1="time" variable_2="value"/>
                  </connection>
                </model>
                """;
    }

    private String cellml20WithImports() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/2.0#"
                    xmlns:xlink="http://www.w3.org/1999/xlink"
                    name="demo_imports"
                    version="2.0">
                  <import xlink:href="other.cellml">
                    <importedComponent component_ref="comp_in_other_file"/>
                    <importedComponent component_ref="another_imported_component"/>
                  </import>
                  <component name="local">
                    <variable name="time" units="second"/>
                  </component>
                </model>
                """;
    }

    private String cellml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.1#"
                    xmlns:cmeta="http://www.cellml.org/metadata/1.0#"
                    xmlns:bqs="http://www.cellml.org/bqs/1.0#"
                    xmlns:dc="http://purl.org/dc/elements/1.1/"
                    xmlns:dcterms="http://purl.org/dc/terms/"
                    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                    xmlns:vCard="http://www.w3.org/2001/vcard-rdf/3.0#"
                    name="demo_cellml"
                    cmeta:id="model_meta">
                  <component name="environment" cmeta:id="environment_meta">
                    <variable name="time" units="second"/>
                  </component>
                  <component name="calcium">
                    <variable name="Ca_i" units="dimensionless" cmeta:id="ca_i_meta" private_interface="in"/>
                    <variable name="Ca_o" units="dimensionless"/>
                    <variable name="substrate" units="dimensionless" cmeta:id="substrate_meta"/>
                    <variable name="product" units="dimensionless" cmeta:id="product_meta"/>
                    <variable name="enzyme" units="dimensionless" cmeta:id="enzyme_meta"/>
                    <variable name="reaction_rate" units="dimensionless" cmeta:id="rate_meta"/>
                    <reaction reversible="yes">
                      <variable_ref variable="substrate">
                        <role role="reactant" direction="forward" stoichiometry="2" delta_variable="reaction_rate"/>
                      </variable_ref>
                      <variable_ref variable="product">
                        <role role="product" direction="forward" stoichiometry="1"/>
                      </variable_ref>
                      <variable_ref variable="enzyme">
                        <role role="catalyst" direction="forward"/>
                      </variable_ref>
                    </reaction>
                  </component>
                  <connection>
                    <map_components component_1="environment" component_2="calcium"/>
                    <map_variables variable_1="time" variable_2="Ca_i"/>
                  </connection>
                  <rdf:RDF>
                    <rdf:Description rdf:about="">
                      <dc:title>Synthetic CellML metadata model</dc:title>
                      <dc:publisher>Example Publisher</dc:publisher>
                      <dc:creator rdf:resource="rdf:#model_creator"/>
                      <bqs:reference rdf:resource="rdf:#pubmed_reference"/>
                      <dcterms:created rdf:resource="rdf:#created_date"/>
                    </rdf:Description>
                    <rdf:Description rdf:about="#environment_meta">
                      <cmeta:comment rdf:resource="rdf:#environment_comment"/>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#environment_comment">
                      <rdf:value>Environment component metadata</rdf:value>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#created_date">
                      <dcterms:W3CDTF>2024-05-20</dcterms:W3CDTF>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#model_creator">
                      <vCard:N rdf:resource="rdf:#model_creator_name"/>
                      <vCard:EMAIL rdf:resource="rdf:#model_creator_email"/>
                      <vCard:ORG rdf:resource="rdf:#model_creator_org"/>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#model_creator_name">
                      <vCard:Given>Ada</vCard:Given>
                      <vCard:Family>Lovelace</vCard:Family>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#model_creator_email">
                      <rdf:value>ada@example.org</rdf:value>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#model_creator_org">
                      <vCard:Orgname>Analytical Engine Group</vCard:Orgname>
                      <vCard:Orgunit>Example Institute</vCard:Orgunit>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#pubmed_reference">
                      <bqs:Pubmed_id>12345</bqs:Pubmed_id>
                      <bqs:JournalArticle rdf:resource="rdf:#article"/>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#article">
                      <dc:title>Synthetic article title</dc:title>
                      <dc:creator rdf:resource="rdf:#article_authors"/>
                      <bqs:Journal rdf:resource="rdf:#journal"/>
                      <dcterms:issued rdf:resource="rdf:#issued"/>
                    </rdf:Description>
                    <rdf:Seq rdf:about="rdf:#article_authors">
                      <rdf:li rdf:resource="rdf:#article_author"/>
                    </rdf:Seq>
                    <rdf:Description rdf:about="rdf:#article_author">
                      <vCard:N rdf:resource="rdf:#article_author_name"/>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#article_author_name">
                      <vCard:Given>Grace</vCard:Given>
                      <vCard:Family>Hopper</vCard:Family>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#journal">
                      <dc:title>Journal of Synthetic Models</dc:title>
                    </rdf:Description>
                    <rdf:Description rdf:about="rdf:#issued">
                      <dcterms:W3CDTF>2024-01-02</dcterms:W3CDTF>
                    </rdf:Description>
                  </rdf:RDF>
                </model>
                """;
    }
}

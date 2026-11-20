package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class ParsedModelGraphMapperTest {
    @Test
    void mapsParsedSbmlModelToDocumentAndModelGraph() {
        var parsedModel = new ParsedModel(
                new ModelReference("model.xml", URI.create("https://example.org/model.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of("sbml.level", "3", "sbml.version", "2")
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.relationships().size());

        var document = graph.nodes().getFirst();
        assertEquals("document", document.key());
        assertTrue(document.labels().contains("Document"));
        assertEquals("model.xml", document.properties().get("fileId"));
        assertEquals("3", document.properties().get("sbml.level"));
        assertEquals("3", graph.nodes().get(1).properties().get("sbmlLevel"));
        assertEquals("2", graph.nodes().get(1).properties().get("sbmlVersion"));

        var model = graph.nodes().get(1);
        assertEquals("model", model.key());
        assertTrue(model.labels().contains("Model"));
        assertTrue(model.labels().contains("SBML_MODEL"));
        assertEquals("m1", model.properties().get("modelId"));
        assertEquals("Example model", model.properties().get("name"));
        assertEquals("model.xml", model.properties().get("fileId"));
        assertEquals("https://example.org/model.xml", model.properties().get("sourceUri"));

        var relationship = graph.relationships().getFirst();
        assertEquals("document", relationship.startKey());
        assertEquals("HAS_MODEL", relationship.type());
        assertEquals("model", relationship.endKey());
    }

    @Test
    void writesVersionIdOnDocumentAndModelOnlyWhenPresent() {
        var uri = URI.create("https://example.org/model.xml");
        var withoutVersion = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("model.xml", uri, ModelType.SBML),
                "m1",
                "Example model",
                Map.of()
        ));

        assertFalse(withoutVersion.nodes().getFirst().properties().containsKey("versionId"));
        assertFalse(withoutVersion.nodes().get(1).properties().containsKey("versionId"));

        var withVersion = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("model.xml", uri, ModelType.SBML, "2014-01-01"),
                "m1",
                "Example model",
                Map.of()
        ));

        assertEquals("2014-01-01", withVersion.nodes().getFirst().properties().get("versionId"));
        assertEquals("2014-01-01", withVersion.nodes().get(1).properties().get("versionId"));
    }

    @Test
    void indexesAggregateSearchTextWithSeparatorFragmentsButKeepsIdentityProperties() {
        var parsedModel = new ParsedModel(
                new ModelReference("model.xml", URI.create("https://example.org/model.xml"), ModelType.SBML),
                "model_with_separators",
                "Model with separators",
                Map.of(),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SPECIES,
                                "species_alpha",
                                "Alpha",
                                Map.of("id", "species_alpha", "name", "Alpha")
                        )
                ),
                List.of()
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        var model = graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow();
        assertTrue(model.properties().get("species").contains("species"), model.properties().get("species"));
        assertTrue(model.properties().get("species").contains("alpha"), model.properties().get("species"));
        assertEquals("model_with_separators", model.properties().get("modelId"));
        assertEquals("Model with separators", model.properties().get("name"));
    }

    @Test
    void mapsParsedSbmlEntitiesToLegacyGraphShape() {
        var parsedModel = new ParsedModel(
                new ModelReference("model.xml", URI.create("https://example.org/model.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of("sbml.level", "3", "sbml.version", "2"),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.COMPARTMENT,
                                "cell",
                                "Cell",
                                Map.of("id", "cell", "name", "Cell")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SPECIES,
                                "s1",
                                "Substrate",
                                Map.of("id", "s1", "name", "Substrate", "compartment", "cell")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SPECIES,
                                "s2",
                                "Product",
                                Map.of("id", "s2", "name", "Product", "compartment", "cell")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.REACTION,
                                "r1",
                                "conversion",
                                Map.of("id", "r1", "name", "conversion")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.PARAMETER,
                                "k1",
                                "Rate",
                                Map.of("id", "k1", "name", "Rate")
                        )
                ),
                List.of(
                        new ParsedReactionParticipant("r1", "s1", ParsedReactionParticipant.Role.REACTANT),
                        new ParsedReactionParticipant("r1", "s2", ParsedReactionParticipant.Role.PRODUCT)
                )
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("compartment:cell") && node.labels().contains("SBML_COMPARTMENT")
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("species:s1") && node.labels().contains("SBML_SPECIES")
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("reaction:r1") && node.labels().contains("SBML_REACTION")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("model")
                        && relationship.type().equals("HAS_SPECIES")
                        && relationship.endKey().equals("species:s1")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("species:s1")
                        && relationship.type().equals("IS_LOCATED_IN")
                        && relationship.endKey().equals("compartment:cell")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("reaction:r1")
                        && relationship.type().equals("HAS_REACTANT")
                        && relationship.endKey().equals("species:s1")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("species:s2")
                        && relationship.type().equals("IS_PRODUCT")
                        && relationship.endKey().equals("reaction:r1")
        ));

        var model = graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow();
        assertEquals("cell Cell", model.properties().get("compartment"));
        assertEquals("s1 Substrate s2 Product", model.properties().get("species"));
        assertEquals("r1 conversion", model.properties().get("reaction"));
        assertEquals("k1 Rate", model.properties().get("parameter"));
    }

    @Test
    void mapsParticipantPropertiesToGraphRelationships() {
        var parsedModel = new ParsedModel(
                new ModelReference("model.xml", URI.create("https://example.org/model.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of("sbml.level", "2", "sbml.version", "4"),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SPECIES,
                                "s1",
                                "Substrate",
                                Map.of("id", "s1", "name", "Substrate")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SPECIES,
                                "s2",
                                "Product",
                                Map.of("id", "s2", "name", "Product")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.REACTION,
                                "r1",
                                "conversion",
                                Map.of("id", "r1", "name", "conversion")
                        )
                ),
                List.of(
                        new ParsedReactionParticipant(
                                "r1", "s1", ParsedReactionParticipant.Role.REACTANT, Map.of("stoichiometry", "2")),
                        new ParsedReactionParticipant(
                                "r1", "s2", ParsedReactionParticipant.Role.PRODUCT, Map.of("stoichiometry", "1"))
                )
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("reaction:r1")
                        && relationship.type().equals("HAS_REACTANT")
                        && relationship.endKey().equals("species:s1")
                        && relationship.properties().get("stoichiometry").equals("2")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("species:s1")
                        && relationship.type().equals("IS_REACTANT")
                        && relationship.endKey().equals("reaction:r1")
                        && relationship.properties().get("stoichiometry").equals("2")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("reaction:r1")
                        && relationship.type().equals("HAS_PRODUCT")
                        && relationship.endKey().equals("species:s2")
                        && relationship.properties().get("stoichiometry").equals("1")
        ));
    }

    @Test
    void keepsBothStoichiometriesWhenSameSpeciesIsReactantAndProduct() {
        var parsedModel = new ParsedModel(
                new ModelReference("model.xml", URI.create("https://example.org/model.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of("sbml.level", "2", "sbml.version", "4"),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SPECIES,
                                "s1",
                                "Substrate",
                                Map.of("id", "s1", "name", "Substrate")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.REACTION,
                                "r1",
                                "conversion",
                                Map.of("id", "r1", "name", "conversion")
                        )
                ),
                List.of(
                        new ParsedReactionParticipant(
                                "r1", "s1", ParsedReactionParticipant.Role.REACTANT, Map.of("stoichiometry", "2")),
                        new ParsedReactionParticipant(
                                "r1", "s1", ParsedReactionParticipant.Role.PRODUCT, Map.of("stoichiometry", "3"))
                )
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("reaction:r1")
                        && relationship.type().equals("HAS_REACTANT")
                        && relationship.endKey().equals("species:s1")
                        && relationship.properties().get("stoichiometry").equals("2")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("reaction:r1")
                        && relationship.type().equals("HAS_PRODUCT")
                        && relationship.endKey().equals("species:s1")
                        && relationship.properties().get("stoichiometry").equals("3")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("species:s1")
                        && relationship.type().equals("IS_PRODUCT")
                        && relationship.endKey().equals("reaction:r1")
                        && relationship.properties().get("stoichiometry").equals("3")
        ));
    }

    @Test
    void mapsParsedAnnotationsToLegacyGraphShape() {
        var parsedModel = new ParsedModel(
                new ModelReference("model.xml", URI.create("https://example.org/model.xml"), ModelType.SBML),
                "m1",
                "Example model",
                Map.of("sbml.level", "3", "sbml.version", "2"),
                List.of(new ParsedModelEntity(
                        ParsedModelEntity.EntityType.SPECIES,
                        "s1",
                        "Substrate",
                        Map.of("id", "s1", "name", "Substrate")
                )),
                List.of(),
                List.of(new ParsedAnnotation(
                        "species:s1",
                        Map.of("created", "2024-01-01T00:00:00Z"),
                        List.of(new ParsedAnnotationResource("https://identifiers.org/go/GO:0008150", "is")),
                        List.of(new ParsedPerson("Jane", "Doe", "jane@example.org", "Example Institute")),
                        List.of("urn:miriam:biomodels.sbo:SBO:0000247")
                ))
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().startsWith("annotation:") && node.labels().contains("ANNOTATION")
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("resource:https://identifiers.org/go/GO:0008150")
                        && node.labels().contains("RESOURCE")
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().startsWith("person:Jane:Doe") && node.labels().contains("PERSON")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("species:s1") && relationship.type().equals("HAS_ANNOTATION")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.type().equals("is")
                        && relationship.endKey().equals("resource:https://identifiers.org/go/GO:0008150")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.type().equals("HAS_SBOTERM")
                        && relationship.endKey().equals("resource:urn:miriam:biomodels.sbo:SBO:0000247")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.type().equals("IS_CREATOR")
        ));

        var model = graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow();
        assertEquals("Jane Doe", model.properties().get("creator"));
        assertEquals("jane@example.org", model.properties().get("email"));
        assertEquals("Example Institute", model.properties().get("organization"));
        assertEquals("https://identifiers.org/go/GO:0008150", model.properties().get("resourceUri"));
    }

    @Test
    void mapsParsedPublicationsToLegacyGraphShapeAndSearchFields() {
        var parsedModel = new ParsedModel(
                new ModelReference("model.xml", URI.create("https://example.org/model.xml"), ModelType.CELLML),
                "m1",
                "Example model",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedPublication(
                        "pubmed:123",
                        "A model publication",
                        "Journal of Models",
                        "A short abstract",
                        "Example University",
                        "2024",
                        List.of(new ParsedPerson("Jane", "Doe", "", ""))
                ))
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("publication:pubmed:123") && node.labels().contains("PUBLICATION")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("model")
                        && relationship.type().equals("HAS_PUBLICATION")
                        && relationship.endKey().equals("publication:pubmed:123")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("publication:pubmed:123")
                        && relationship.type().equals("HAS_AUTHOR")
                        && relationship.endKey().equals("person:Jane:Doe:")
        ));

        var model = graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow();
        assertEquals("Jane Doe", model.properties().get("author"));
        assertEquals("A model publication", model.properties().get("title"));
        assertEquals("Journal of Models", model.properties().get("journal"));
        assertEquals("2024", model.properties().get("year"));
    }

    @Test
    void mapsParsedCellmlEntitiesToLegacyGraphShapeAndSearchFields() {
        var parsedModel = new ParsedModel(
                new ModelReference("model.cellml", URI.create("https://example.org/model.cellml"), ModelType.CELLML),
                "m1",
                "Example CellML model",
                Map.of("cellml.components", "1", "cellml.variables", "1"),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.COMPONENT,
                                "environment",
                                "environment",
                                Map.of("id", "environment", "name", "environment")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.VARIABLE,
                                "environment/time",
                                "time",
                                Map.of("id", "environment/time", "name", "time", "component", "environment")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.VARIABLE,
                                "environment/clock",
                                "clock",
                                Map.of("id", "environment/clock", "name", "clock", "component", "environment")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.CELLML_REACTION,
                                "environment/reaction-0",
                                "environment/reaction-0",
                                Map.of("id", "environment/reaction-0", "name", "environment/reaction-0", "component", "environment", "reversible", "true")
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.VARIABLE + ":environment/time",
                        "IS_CONNECTED_TO",
                        ParsedModelEntity.EntityType.VARIABLE + ":environment/clock",
                        Map.of("component1", "environment", "variable1", "time", "component2", "environment", "variable2", "clock")
                ), new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.COMPONENT + ":environment",
                        "HAS_REACTION",
                        ParsedModelEntity.EntityType.CELLML_REACTION + ":environment/reaction-0",
                        Map.of()
                ), new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.CELLML_REACTION + ":environment/reaction-0",
                        "HAS_REACTANT",
                        ParsedModelEntity.EntityType.VARIABLE + ":environment/time",
                        Map.of("role", "reactant", "direction", "forward", "stoichiometry", "1")
                ), new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.CELLML_REACTION + ":environment/reaction-0",
                        "HAS_DELTA_VAR",
                        ParsedModelEntity.EntityType.VARIABLE + ":environment/clock",
                        Map.of("deltaVariable", "clock")
                ))
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("component:environment") && node.labels().contains("CELLML_COMPONENT")
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("variable:environment/time") && node.labels().contains("CELLML_VARIABLE")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("model")
                        && relationship.type().equals("HAS_COMPONENT")
                        && relationship.endKey().equals("component:environment")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("variable:environment/time")
                        && relationship.type().equals("IS_CONNECTED_TO")
                        && relationship.endKey().equals("variable:environment/clock")
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("cellml_reaction:environment/reaction-0") && node.labels().contains("CELLML_REACTION")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("component:environment")
                        && relationship.type().equals("HAS_REACTION")
                        && relationship.endKey().equals("cellml_reaction:environment/reaction-0")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("cellml_reaction:environment/reaction-0")
                        && relationship.type().equals("HAS_REACTANT")
                        && relationship.endKey().equals("variable:environment/time")
                        && relationship.properties().get("stoichiometry").equals("1")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("cellml_reaction:environment/reaction-0")
                        && relationship.type().equals("HAS_DELTA_VAR")
                        && relationship.endKey().equals("variable:environment/clock")
                        && relationship.properties().get("deltaVariable").equals("clock")
        ));

        var model = graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow();
        assertEquals("environment", model.properties().get("component"));
        // QRY-5: expansion is additive, so separator-bearing aggregate values keep their original text
        // and additionally carry the separator-split fragments.
        assertEquals("environment/time time environment/clock clock environment", model.properties().get("variable"));
    }

    @Test
    void mapsParsedSedmlEntitiesToLegacyGraphShape() {
        var parsedModel = new ParsedModel(
                new ModelReference("simulation.sedml", URI.create("https://example.org/simulation.sedml"), ModelType.SEDML),
                "simulation.sedml",
                "simulation.sedml",
                Map.of("sedml.models", "1"),
                List.of(
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_MODELREFERENCE,
                                "model1",
                                "",
                                Map.of("id", "model1", "name", "", "modelSource", "model.xml", "modelChanged", "false")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_SIMULATION,
                                "sim1",
                                "",
                                Map.of("id", "sim1", "name", "", "simType", "uniformTimeCourse", "simKisao", "KISAO:0000019")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_DATAGENERATOR,
                                "dg1",
                                "",
                                Map.of("id", "dg1", "name", "", "math", "species_count")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_TASK,
                                "task1",
                                "",
                                Map.of("id", "task1", "name", "", "modelReference", "model1", "simulationReference", "sim1")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_VARIABLE,
                                "var1",
                                "",
                                Map.of("id", "var1", "name", "", "target", "/sbml:sbml/sbml:model", "reference", "task1")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_OUTPUT,
                                "plot1",
                                "",
                                Map.of("id", "plot1", "name", "", "outputType", "plot2D")
                        ),
                        new ParsedModelEntity(
                                ParsedModelEntity.EntityType.SEDML_DATASET,
                                "dataset1",
                                "",
                                Map.of("id", "dataset1", "name", "species", "dataLabel", "species")
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(new ParsedEntityRelationship(
                        ParsedModelEntity.EntityType.SEDML_VARIABLE + ":var1",
                        "CALCULATES_MODEL",
                        ParsedModelEntity.EntityType.SEDML_MODELREFERENCE + ":model1",
                        Map.of()
                ))
        );

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("sedml_modelreference:model1") && node.labels().contains("SEDML_MODELREFERENCE")
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("sedml_variable:var1") && node.labels().contains("SEDML_VARIABLE")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("model")
                        && relationship.type().equals("HAS_MODELREFERENCE")
                        && relationship.endKey().equals("sedml_modelreference:model1")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("sedml_variable:var1")
                        && relationship.type().equals("CALCULATES_MODEL")
                        && relationship.endKey().equals("sedml_modelreference:model1")
        ));

        var model = graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow();
        assertEquals("model.xml", model.properties().get("modelSource"));
        assertEquals("plot2D", model.properties().get("outputType"));
        assertEquals("species", model.properties().get("dataLabel"));
        assertEquals("KISAO:0000019", model.properties().get("simKisao"));
        assertEquals("uniformTimeCourse", model.properties().get("simType"));
        assertEquals("species_count", model.properties().get("math"));
    }

    // ANNO-1a: SED-ML puts the algorithm term in an attribute, so the mapper derives the RESOURCE
    // node from it. The catalogue's term key is the only source of the URI spelling.
    @Test
    void mapsKisaoIdOfACommittedSedmlSampleToAHasKisaoResource() throws Exception {
        var fixture = Path.of("src", "test", "resources", "sedml-fixtures", "non-uniform", "steadyState-metabolicControlAnalysis.sedml");
        var parsed = new SedmlModelParser().parse(new ModelSource(new ModelReference(
                fixture.getFileName().toString(),
                fixture.toUri(),
                ModelType.SEDML
        )), Files.newInputStream(fixture));

        assertEquals("KISAO:0000407", parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_SIMULATION)
                .findFirst()
                .orElseThrow()
                .properties()
                .get("simKisao"));

        var graph = new ParsedModelGraphMapper().toGraph(parsed);

        var resources = graph.nodes().stream()
                .filter(node -> node.labels().contains("RESOURCE"))
                .filter(node -> node.properties().get("uri").contains("kisao"))
                .toList();
        assertEquals(1, resources.size(), graph.nodes().toString());
        var resource = resources.getFirst();
        assertTrue(resource.key().equals("resource:urn:miriam:biomodels.kisao:KISAO_0000407"), resource.key());
        assertEquals("urn:miriam:biomodels.kisao:KISAO_0000407", resource.properties().get("uri"));

        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("sedml_simulation:sim0")
                        && relationship.type().equals("HAS_KISAO")
                        && relationship.endKey().equals(resource.key())
        ), graph.relationships().toString());
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals(resource.key())
                        && relationship.type().equals("BELONGS_TO")
                        && relationship.endKey().equals("sedml_simulation:sim0")
        ), graph.relationships().toString());

        // Answer 15: the term stays out of the annotation search property.
        var model = graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow();
        assertFalse(model.properties().getOrDefault("resourceUri", "").contains("kisao"), model.properties().toString());
    }

    @Test
    void writesOneKisaoResourceForBothIdSpellingsAndForRepeatedReferences() {
        var parsedModel = sedmlWithSimulations(Map.of(
                "sim_colon", "KISAO:0000019",
                "sim_underscore", "KISAO_0000019"
        ));

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        var resources = graph.nodes().stream()
                .filter(node -> node.labels().contains("RESOURCE"))
                .toList();
        assertEquals(1, resources.size(), graph.nodes().toString());
        assertEquals("urn:miriam:biomodels.kisao:KISAO_0000019", resources.getFirst().properties().get("uri"));

        var resourceKey = resources.getFirst().key();
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("sedml_simulation:sim_colon")
                        && relationship.type().equals("HAS_KISAO")
                        && relationship.endKey().equals(resourceKey)
        ), graph.relationships().toString());
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("sedml_simulation:sim_underscore")
                        && relationship.type().equals("HAS_KISAO")
                        && relationship.endKey().equals(resourceKey)
        ), graph.relationships().toString());
        // One node, two owners: each simulation gets its own BELONGS_TO back.
        assertEquals(2, graph.relationships().stream()
                .filter(relationship -> relationship.startKey().equals(resourceKey))
                .filter(relationship -> relationship.type().equals("BELONGS_TO"))
                .count());
    }

    @Test
    void writesNoKisaoResourceWhenTheAlgorithmCarriesNoKisaoId() {
        var parsedModel = sedmlWithSimulations(Map.of(
                "sim_blank", "",
                "sim_with_id", "KISAO:0000019"
        ));

        var graph = new ParsedModelGraphMapper().toGraph(parsedModel);

        assertFalse(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals("sedml_simulation:sim_blank")
                        && relationship.type().equals("HAS_KISAO")
        ), graph.relationships().toString());

        // And a document where no simulation carries a term writes no KISAO resource at all.
        var withoutAnyTerm = new ParsedModelGraphMapper().toGraph(sedmlWithSimulations(Map.of("sim_blank", "")));
        assertTrue(withoutAnyTerm.nodes().stream()
                .filter(node -> node.labels().contains("RESOURCE"))
                .noneMatch(node -> node.properties().get("uri").contains("kisao")), withoutAnyTerm.nodes().toString());
        assertTrue(withoutAnyTerm.relationships().stream()
                .noneMatch(relationship -> relationship.type().equals("HAS_KISAO")), withoutAnyTerm.relationships().toString());
    }

    // CML-3: the external-cmeta keywords sit on the model's annotation, which is in no full-text
    // index, so the mapper aggregates them onto the model node in document order.
    @Test
    void aggregatesKeywordsOfAllAnnotationsOntoTheModelInDocumentOrder() {
        var graph = new ParsedModelGraphMapper().toGraph(cellmlWithAnnotationProperties(List.of(
                Map.of("title", "First cmeta", "keywords", "calcium handling electrophysiology"),
                Map.of("keywords", "signalling calcium")
        )));

        var model = modelNode(graph);
        assertEquals("calcium handling electrophysiology signalling", model.properties().get("keywords"));
    }

    @Test
    void collapsesDuplicateKeywordTokensAndIgnoresBlankOnes() {
        var graph = new ParsedModelGraphMapper().toGraph(cellmlWithAnnotationProperties(List.of(
                Map.of("keywords", "  calcium   handling "),
                Map.of("keywords", "calcium  ELECTROPHYSIOLOGY   "),
                Map.of("title", "no keywords here")
        )));

        assertEquals("calcium handling ELECTROPHYSIOLOGY", modelNode(graph).properties().get("keywords"));
    }

    @Test
    void writesNoKeywordsPropertyWhenNoAnnotationCarriesAny() {
        var withoutKeywords = new ParsedModelGraphMapper().toGraph(cellmlWithAnnotationProperties(List.of(
                Map.of("title", "First cmeta"),
                Map.of("keywords", "   ")
        )));

        var model = modelNode(withoutKeywords);
        assertFalse(model.properties().containsKey("keywords"), model.properties().toString());

        var withoutAnnotations = new ParsedModelGraphMapper().toGraph(new ParsedModel(
                new ModelReference("model.cellml", URI.create("https://example.org/model.cellml"), ModelType.CELLML),
                "m1",
                "Example CellML model",
                Map.of()
        ));
        assertFalse(modelNode(withoutAnnotations).properties().containsKey("keywords"));
    }

    @Test
    void keepsTheKeywordsPropertyOnTheAnnotationItCameFrom() {
        var graph = new ParsedModelGraphMapper().toGraph(cellmlWithAnnotationProperties(List.of(
                Map.of("keywords", "calcium handling"),
                Map.of("keywords", "signalling")
        )));

        var annotations = graph.nodes().stream()
                .filter(node -> node.labels().contains("ANNOTATION"))
                .toList();
        assertEquals(2, annotations.size(), graph.nodes().toString());
        assertEquals("calcium handling", annotations.get(0).properties().get("keywords"));
        assertEquals("signalling", annotations.get(1).properties().get("keywords"));
        assertEquals("calcium handling signalling", modelNode(graph).properties().get("keywords"));
    }

    /** One model annotation per given property map, so the mapper sees document-ordered keywords. */
    private ParsedModel cellmlWithAnnotationProperties(List<Map<String, String>> annotationProperties) {
        var annotations = annotationProperties.stream()
                .map(properties -> new ParsedAnnotation("model", properties, List.of(), List.of(), List.of()))
                .toList();
        return new ParsedModel(
                new ModelReference("model.cellml", URI.create("https://example.org/model.cellml"), ModelType.CELLML),
                "m1",
                "Example CellML model",
                Map.of(),
                List.of(),
                List.of(),
                annotations,
                List.of(),
                List.of()
        );
    }

    private static ExtractedGraphNode modelNode(ExtractedModelGraph graph) {
        return graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow();
    }

    private ParsedModel sedmlWithSimulations(Map<String, String> kisaoBySimulationId) {        var entities = kisaoBySimulationId.entrySet().stream()
                .map(entry -> new ParsedModelEntity(
                        ParsedModelEntity.EntityType.SEDML_SIMULATION,
                        entry.getKey(),
                        "",
                        Map.of(
                                "id", entry.getKey(),
                                "name", "",
                                "simType", "uniformTimeCourse",
                                "simKisao", entry.getValue()
                        )
                ))
                .toList();
        return new ParsedModel(
                new ModelReference("simulation.sedml", URI.create("https://example.org/simulation.sedml"), ModelType.SEDML),
                "simulation.sedml",
                "simulation.sedml",
                Map.of("sedml.simulations", Integer.toString(kisaoBySimulationId.size())),
                entities,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}

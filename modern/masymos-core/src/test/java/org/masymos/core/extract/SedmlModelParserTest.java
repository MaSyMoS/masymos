package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class SedmlModelParserTest {
    private ModelSource source;

    @BeforeEach
    void createSource() {
        source = new ModelSource(new ModelReference(
                "test.sedml",
                URI.create("https://example.org/test.sedml"),
                ModelType.SEDML
        ));
    }

    @Test
    void parsesLegacyRelevantSedmlStructures() {
        var parsed = new SedmlModelParser().parse(source, stream(sedml()));

        assertEquals("test.sedml", parsed.modelId());
        assertEquals("test.sedml", parsed.modelName());
        assertEquals("1", parsed.properties().get("sedml.models"));
        assertEquals("1", parsed.properties().get("sedml.simulations"));
        assertEquals("1", parsed.properties().get("sedml.tasks"));
        assertEquals("1", parsed.properties().get("sedml.dataGenerators"));
        assertEquals("2", parsed.properties().get("sedml.outputs"));
        assertEquals("1", parsed.properties().get("sedml.variables"));
        assertEquals("0", parsed.properties().get("sedml.parameterTargets"));

        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_MODELREFERENCE
                        && entity.id().equals("model1")
                        && entity.properties().get("modelSource").equals("model.xml")
        ));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_SIMULATION
                        && entity.id().equals("sim1")
                        && entity.properties().get("simKisao").equals("KISAO:0000019")
                        && entity.properties().get("outputEndTime").equals("10")
                        && entity.properties().get("numberOfPoints").equals("100")
        ));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_VARIABLE
                        && entity.id().equals("var1")
                        && entity.properties().get("target").equals("/sbml:sbml/sbml:model")
                        && entity.properties().get("symbol").equals("urn:sedml:symbol:time")
                        && entity.properties().get("targetType").equals("unknown")
        ));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_CURVE
                        && entity.id().equals("curve1")
                        && entity.properties().get("logX").equals("false")
                        && entity.properties().get("logY").equals("true")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_TASK:task1")
                        && relationship.type().equals("REFERENCES_MODEL")
                        && relationship.endEntityKey().equals("SEDML_MODELREFERENCE:model1")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_OUTPUT:plot1")
                        && relationship.type().equals("HAS_CURVE")
                        && relationship.endEntityKey().equals("SEDML_CURVE:curve1")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_VARIABLE:var1")
                        && relationship.type().equals("CALCULATES_MODEL")
                        && relationship.endEntityKey().equals("SEDML_MODELREFERENCE:model1")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_MODELREFERENCE:model1")
                        && relationship.type().equals("USED_IN_DATAGENERATOR")
                        && relationship.endEntityKey().equals("SEDML_VARIABLE:var1")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_OUTPUT:plot3d")
                        && relationship.type().equals("HAS_SURFACE")
                        && relationship.endEntityKey().equals("SEDML_SURFACE:surface1")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_CURVE:curve1")
                        && relationship.type().equals("IS_ENTITY_OF")
                        && relationship.endEntityKey().equals("SEDML_MODELREFERENCE:model1")
        ));
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_SURFACE:surface1")
                        && relationship.type().equals("IS_ENTITY_OF")
                        && relationship.endEntityKey().equals("SEDML_MODELREFERENCE:model1")
        ));
    }

    @Test
    void parsesProvidedSedmlSamplesWhenAvailable() throws Exception {
        var samples = List.of(
                Path.of("..", "..", "samples", "sedml", "BIOMD0000000003sedml.xml"),
                Path.of("..", "..", "samples", "sedml", "tysonCellML_simulation.xml")
        );

        for (Path sample : samples) {
            Assumptions.assumeTrue(Files.isRegularFile(sample));
            try (var stream = Files.newInputStream(sample)) {
                var parsed = new SedmlModelParser().parse(new ModelSource(new ModelReference(
                        sample.getFileName().toString(),
                        sample.toUri(),
                        ModelType.SEDML
                )), stream);

                assertTrue(parsed.entities().stream().anyMatch(entity ->
                        entity.type() == ParsedModelEntity.EntityType.SEDML_MODELREFERENCE
                ));
            }
        }
    }

    @Test
    void extractsMathMlApplyWithOperandsAndOperator() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <apply>
                  <divide/>
                  <ci>s</ci>
                  <ci>t</ci>
                </apply>
                """)));

        var math = mathProperty(parsed);
        assertTrue(math.contains("s"), math);
        assertTrue(math.contains("t"), math);
        assertTrue(math.contains("/"), math);
        assertEquals("( / s t )", math);
        assertEquals("false", mathLossyProperty(parsed));
    }

    @Test
    void extractsMathMlNumberLiteral() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <apply>
                  <times/>
                  <cn>2</cn>
                  <ci>s</ci>
                </apply>
                """)));

        var math = mathProperty(parsed);
        assertTrue(math.contains("2"), math);
        assertEquals("( * 2 s )", math);
        assertEquals("false", mathLossyProperty(parsed));
    }

    @Test
    void rendersPiecewiseWithOtherwiseAndFlagsItComplete() {
        // <piece> lists the value first and the guard second: "0 if s < 0".
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <piecewise>
                  <piece>
                    <cn>0</cn>
                    <apply><lt/><ci>s</ci><cn>0</cn></apply>
                  </piece>
                  <otherwise>
                    <ci>s</ci>
                  </otherwise>
                </piecewise>
                """)));

        assertEquals("( (if ( < s 0 ) 0) (otherwise s) )", mathProperty(parsed));
        assertEquals("false", mathLossyProperty(parsed));
    }

    @Test
    void rendersLambdaWithBandVar() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <lambda>
                  <bvar><ci>x</ci></bvar>
                  <apply><plus/><ci>x</ci><cn>1</cn></apply>
                </lambda>
                """)));

        assertEquals("(lambda x ( + x 1 ))", mathProperty(parsed));
        assertEquals("false", mathLossyProperty(parsed));
    }

    @Test
    void rendersNthrootWithDegree() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <apply>
                  <root/>
                  <degree><cn>3</cn></degree>
                  <ci>s</ci>
                </apply>
                """)));

        assertEquals("( root 3 s )", mathProperty(parsed));
        assertEquals("false", mathLossyProperty(parsed));
    }

    @Test
    void rendersNarySumAndProductWithBoundVariables() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <apply>
                  <sum/>
                  <bvar><ci>i</ci></bvar>
                  <apply><product/><ci>i</ci><ci>s</ci></apply>
                </apply>
                """)));

        assertEquals("( sum (binding i) ( product i s ) )", mathProperty(parsed));
        assertEquals("false", mathLossyProperty(parsed));
    }

    @Test
    void rendersLogicalRelationalAndFunctionOperators() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <apply>
                  <not/>
                  <apply><and/>
                    <apply><neq/><ci>s</ci><cn>0</cn></apply>
                    <apply><geq/><apply><ln/><ci>s</ci></apply><ci>t</ci></apply>
                  </apply>
                </apply>
                """)));

        assertEquals("( not ( and ( != s 0 ) ( >= ( ln s ) t ) ) )", mathProperty(parsed));
        assertEquals("false", mathLossyProperty(parsed));
    }

    @Test
    void rendersNamedConstantsWithoutFlaggingThem() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <apply>
                  <plus/>
                  <pi/>
                  <exponentiale/>
                </apply>
                """)));

        assertEquals("( + pi exponentiale )", mathProperty(parsed));
        assertEquals("false", mathLossyProperty(parsed));
    }

    @Test
    void flagsInventedElementAsLossyAndKeepsItsText() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithMath("""
                <apply>
                  <plus/>
                  <ci>s</ci>
                  <foobar><ci>t</ci></foobar>
                </apply>
                """)));

        assertEquals("true", mathLossyProperty(parsed));
        var math = mathProperty(parsed);
        assertTrue(math.contains("s"), math);
        assertTrue(math.contains("t"), math);
    }

    @Test
    void flagsEveryDataGeneratorOfARealSampleAsNotLossy() throws Exception {
        var sample = Path.of("..", "..", "samples", "sedml", "BIOMD0000000003sedml.xml");
        Assumptions.assumeTrue(Files.isRegularFile(sample));

        ParsedModel parsed;
        try (var stream = Files.newInputStream(sample)) {
            parsed = new SedmlModelParser().parse(new ModelSource(new ModelReference(
                    sample.getFileName().toString(),
                    sample.toUri(),
                    ModelType.SEDML
            )), stream);
        }

        var withMath = parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_DATAGENERATOR)
                .filter(entity -> entity.properties().containsKey("math"))
                .toList();
        assertFalse(withMath.isEmpty(), "sample must contain data generators");
        for (var dataGenerator : withMath) {
            assertEquals("false", dataGenerator.properties().get("mathLossy"), dataGenerator.id());
        }
    }

    @Test
    void marksTheAxisWhoseReferenceIsTheLiteralTimeAxis() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithCurves(
                "<curve id=\"timeOnX\" xDataReference=\"time\" yDataReference=\"dg1\"/>",
                "<curve id=\"timeOnY\" xDataReference=\"dg1\" yDataReference=\"time\"/>",
                "<curve id=\"generatorOnBoth\" xDataReference=\"dg1\" yDataReference=\"dg1\"/>"
        )));

        var timeOnX = curveProperties(parsed, "timeOnX");
        assertEquals("true", timeOnX.get("xAxisIsTime"), timeOnX.toString());
        assertFalse(timeOnX.containsKey("yAxisIsTime"), timeOnX.toString());

        var timeOnY = curveProperties(parsed, "timeOnY");
        assertFalse(timeOnY.containsKey("xAxisIsTime"), timeOnY.toString());
        assertEquals("true", timeOnY.get("yAxisIsTime"), timeOnY.toString());

        var generatorOnBoth = curveProperties(parsed, "generatorOnBoth");
        assertFalse(generatorOnBoth.containsKey("xAxisIsTime"), generatorOnBoth.toString());
        assertFalse(generatorOnBoth.containsKey("yAxisIsTime"), generatorOnBoth.toString());
    }

    @Test
    void matchesTheLiteralTimeAxisCaseInsensitively() {
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithCurves(
                "<curve id=\"mixedCase\" xDataReference=\"TiMe\" yDataReference=\"TIME\"/>"
        )));

        var mixedCase = curveProperties(parsed, "mixedCase");
        assertEquals("true", mixedCase.get("xAxisIsTime"), mixedCase.toString());
        assertEquals("true", mixedCase.get("yAxisIsTime"), mixedCase.toString());
    }

    @Test
    void marksTheTimeReferenceTextuallyEvenIfADataGeneratorSharesTheName() {
        // The rule is textual, exactly as legacy's was (SEDMLExtractor.java:195,204): a reference that
        // reads "time" is the sentinel even when the document also declares a data generator with that
        // id. The reference text cannot tell the two apart and legacy did not try to; SED-ML reserves
        // the reference for a data generator id, so the document is already ambiguous. What is pinned
        // here is that the collision does not silently unmark the axis.
        var parsed = new SedmlModelParser().parse(source, stream(sedmlWithCurves(
                "<curve id=\"refersToTime\" xDataReference=\"time\" yDataReference=\"dg1\"/>"
        ).replace("<dataGenerator id=\"dg1\">", """
                <dataGenerator id="time">
                        <listOfVariables>
                          <variable id="var1" taskReference="task1" symbol="urn:sedml:symbol:time"/>
                        </listOfVariables>
                        <math xmlns="http://www.w3.org/1998/Math/MathML"><ci>var1</ci></math>
                      </dataGenerator>
                      <dataGenerator id="dg1">
                """)));

        var properties = curveProperties(parsed, "refersToTime");
        assertEquals("true", properties.get("xAxisIsTime"), properties.toString());
        assertFalse(properties.containsKey("yAxisIsTime"), properties.toString());

        // Legacy's else-branch: the time axis resolves to no model, so only the dg1 axis contributes.
        assertEquals(1, parsed.entityRelationships().stream()
                .filter(relationship -> relationship.startEntityKey().equals("SEDML_CURVE:refersToTime"))
                .filter(relationship -> relationship.type().equals("IS_ENTITY_OF"))
                .count(), parsed.entityRelationships().toString());
    }

    @Test
    void recordsSedmlNamespaceAndModelLanguage() {
        var parsed = new SedmlModelParser().parse(source, stream("""
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version4" level="1" version="4">
                  <listOfModels>
                    <model id="model1" language="urn:sedml:language:sbml" source="model.xml"/>
                  </listOfModels>
                </sedML>
                """));

        assertEquals("http://sed-ml.org/sed-ml/level1/version4", parsed.properties().get("sedml.namespace"));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_MODELREFERENCE
                        && entity.id().equals("model1")
                        && entity.properties().get("language").equals("urn:sedml:language:sbml")
                        && entity.properties().get("modelSource").equals("model.xml")
        ));
    }

    @Test
    void resolvesRelativeModelSourceAgainstArchiveEntryUri() {
        var parsed = parseWithDocumentUri("file:///data/x.omex!/sedml/a.sedml", "../models/a.sbml");

        assertEquals("../models/a.sbml", modelProperty(parsed, "modelSource"));
        assertEquals("file:/data/x.omex!/models/a.sbml", modelProperty(parsed, "resolvedSource"));
    }

    @Test
    void resolvesRelativeModelSourceAgainstPlainFileUri() {
        var parsed = parseWithDocumentUri("file:///tmp/sim.sedml", "model.xml");

        assertEquals("model.xml", modelProperty(parsed, "modelSource"));
        assertEquals("file:/tmp/model.xml", modelProperty(parsed, "resolvedSource"));
    }

    @Test
    void keepsAbsoluteModelSourceWithoutResolvedProperty() {
        var parsed = parseWithDocumentUri("file:///tmp/sim.sedml", "https://example.org/model.xml");

        assertEquals("https://example.org/model.xml", modelProperty(parsed, "modelSource"));
        assertFalse(modelEntity(parsed).properties().containsKey("resolvedSource"));
    }

    @Test
    void skipsResolutionWhenDocumentUriHasNoScheme() {
        var parsed = parseWithDocumentUri("model.sedml", "model.xml");

        assertEquals("model.xml", modelProperty(parsed, "modelSource"));
        assertFalse(modelEntity(parsed).properties().containsKey("resolvedSource"));
    }

    @Test
    void parsesCommittedNonUniformSteadyStateFixture() throws Exception {
        var fixture = Path.of("src", "test", "resources", "sedml-fixtures", "non-uniform", "steadyState-metabolicControlAnalysis.sedml");
        var parsed = parseFixture(fixture);

        // DATA-3: the document parses to a model with its task, and the task keeps its reference to
        // the non-uniform simulation. Source: SED-ML test-suite HARMONY 2021 metabolic-control
        // analysis example, reduced to its L1 V4 subset (see the report for the exact reduction).
        assertEquals("1", parsed.properties().get("sedml.tasks"));
        assertEquals("3", parsed.properties().get("sedml.dataGenerators"));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_TASK
                        && entity.id().equals("task0")
                        && entity.properties().get("simulationReference").equals("sim0")
        ), parsed.entities().toString());
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_DATAGENERATOR
                        && entity.id().equals("report_0_0_0")
                        && entity.properties().get("mathLossy").equals("false")
        ), parsed.entities().toString());

        // SEDML-1, asserted: <steadyState> is a simulation node like <uniformTimeCourse>. The element
        // carries no time attributes, so none may appear; the task's reference and the edge set follow
        // from the task's simulationReference unchanged.
        assertEquals("1", parsed.properties().get("sedml.simulations"), parsed.entities().toString());
        var steadyState = simulationEntity(parsed, "sim0");
        assertEquals("steadyState", steadyState.properties().get("simType"));
        assertEquals("KISAO:0000407", steadyState.properties().get("simKisao"));
        assertEquals("", steadyState.properties().get("name"));
        assertFalse(steadyState.properties().containsKey("initialTime"), steadyState.properties().toString());
        assertFalse(steadyState.properties().containsKey("outputStartTime"), steadyState.properties().toString());
        assertFalse(steadyState.properties().containsKey("outputEndTime"), steadyState.properties().toString());
        assertFalse(steadyState.properties().containsKey("numberOfPoints"), steadyState.properties().toString());
        assertFalse(steadyState.properties().containsKey("step"), steadyState.properties().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_TASK:task0")
                        && relationship.type().equals("REFERENCES_SIMULATION")
                        && relationship.endEntityKey().equals("SEDML_SIMULATION:sim0")
        ), parsed.entityRelationships().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_SIMULATION:sim0")
                        && relationship.type().equals("SIMULATES")
                        && relationship.endEntityKey().equals("SEDML_MODELREFERENCE:model0")
        ), parsed.entityRelationships().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_MODELREFERENCE:model0")
                        && relationship.type().equals("IS_SIMULATED")
                        && relationship.endEntityKey().equals("SEDML_SIMULATION:sim0")
        ), parsed.entityRelationships().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_SIMULATION:sim0")
                        && relationship.type().equals("IS_REFERENCED_IN_TASK")
                        && relationship.endEntityKey().equals("SEDML_TASK:task0")
        ), parsed.entityRelationships().toString());
    }

    @Test
    void parsesCommittedNonUniformOneStepFixture() throws Exception {
        var fixture = Path.of("src", "test", "resources", "sedml-fixtures", "non-uniform", "oneStep-constructed.sedml");
        var parsed = parseFixture(fixture);

        assertEquals("1", parsed.properties().get("sedml.tasks"));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_TASK
                        && entity.id().equals("task1")
                        && entity.properties().get("simulationReference").equals("sim1")
        ), parsed.entities().toString());

        // SEDML-1, asserted: <oneStep> is a simulation node carrying its step attribute.
        assertEquals("1", parsed.properties().get("sedml.simulations"), parsed.entities().toString());
        var oneStep = simulationEntity(parsed, "sim1");
        assertEquals("oneStep", oneStep.properties().get("simType"));
        assertEquals("KISAO:0000019", oneStep.properties().get("simKisao"));
        assertEquals("0.1", oneStep.properties().get("step"));
        assertFalse(oneStep.properties().containsKey("initialTime"), oneStep.properties().toString());
        assertFalse(oneStep.properties().containsKey("numberOfPoints"), oneStep.properties().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_TASK:task1")
                        && relationship.type().equals("REFERENCES_SIMULATION")
                        && relationship.endEntityKey().equals("SEDML_SIMULATION:sim1")
        ), parsed.entityRelationships().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_SIMULATION:sim1")
                        && relationship.type().equals("SIMULATES")
                        && relationship.endEntityKey().equals("SEDML_MODELREFERENCE:model1")
        ), parsed.entityRelationships().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_MODELREFERENCE:model1")
                        && relationship.type().equals("IS_SIMULATED")
                        && relationship.endEntityKey().equals("SEDML_SIMULATION:sim1")
        ), parsed.entityRelationships().toString());
    }

    @Test
    void omitsStepPropertyWhenOneStepHasNoStepAttribute() {
        var parsed = new SedmlModelParser().parse(source, stream("""
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version4" level="1" version="4">
                  <listOfSimulations>
                    <oneStep id="sim1">
                      <algorithm kisaoID="KISAO:0000019"/>
                    </oneStep>
                  </listOfSimulations>
                </sedML>
                """));

        assertEquals("1", parsed.properties().get("sedml.simulations"));
        var oneStep = simulationEntity(parsed, "sim1");
        assertEquals("oneStep", oneStep.properties().get("simType"));
        assertEquals("KISAO:0000019", oneStep.properties().get("simKisao"));
        assertFalse(oneStep.properties().containsKey("step"), oneStep.properties().toString());
    }

    private ParsedModelEntity simulationEntity(ParsedModel parsed, String id) {
        return parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_SIMULATION)
                .filter(entity -> entity.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SEDML_SIMULATION " + id + ": " + parsed.entities()));
    }

    @Test
    void parsesCommittedRepeatedTaskFixture() throws Exception {
        var fixture = Path.of("src", "test", "resources", "sedml-fixtures", "repeated-task", "repeatedTask-surfaceMesh.sedml");
        var parsed = parseFixture(fixture);

        assertEquals("1", parsed.properties().get("sedml.simulations"));
        assertEquals("3", parsed.properties().get("sedml.dataGenerators"));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_OUTPUT
                        && entity.id().equals("plot_0")
                        && entity.properties().get("outputType").equals("plot3D")
        ), parsed.entities().toString());
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                entity.type() == ParsedModelEntity.EntityType.SEDML_SURFACE
                        && entity.id().equals("plot_0__plot_0_0_0__plot_0_0_1__plot_0_0_2")
                        && entity.properties().get("xData").equals("plot_0_0_0")
        ), parsed.entities().toString());

        // SEDML-2: <repeatedTask id="task1"> is a task like any other, so there are two tasks.
        assertEquals("2", parsed.properties().get("sedml.tasks"), parsed.entities().toString());
        assertEquals("1", parsed.properties().get("sedml.ranges"), parsed.entities().toString());
        assertEquals("1", parsed.properties().get("sedml.changes"), parsed.entities().toString());

        var plainTask = taskEntity(parsed, "task0");
        assertEquals("model0", plainTask.properties().get("modelReference"));
        assertEquals("sim0", plainTask.properties().get("simulationReference"));
        assertFalse(plainTask.properties().containsKey("repeated"), plainTask.properties().toString());

        var repeatedTask = taskEntity(parsed, "task1");
        assertEquals("true", repeatedTask.properties().get("repeated"));
        assertEquals("uniform_linear_for_n", repeatedTask.properties().get("range"));
        assertEquals("true", repeatedTask.properties().get("resetModel"));
        assertEquals("", repeatedTask.properties().get("modelReference"));
        assertEquals("", repeatedTask.properties().get("simulationReference"));

        var range = parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_RANGE)
                .filter(entity -> entity.id().equals("uniform_linear_for_n"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SEDML_RANGE uniform_linear_for_n: " + parsed.entities()));
        assertEquals("uniformRange", range.properties().get("rangeType"));
        assertEquals("", range.properties().get("name"));
        assertEquals("1", range.properties().get("start"));
        assertEquals("15", range.properties().get("end"));
        assertEquals("25", range.properties().get("numberOfSteps"));
        assertEquals("linear", range.properties().get("type"));
        assertFalse(range.properties().containsKey("values"), range.properties().toString());

        var changes = parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_CHANGE)
                .toList();
        assertEquals(1, changes.size(), parsed.entities().toString());
        var change = changes.get(0);
        assertEquals("setValue-0", change.id());
        assertEquals("setValue", change.properties().get("changeType"));
        assertEquals("model0", change.properties().get("modelReference"));
        assertEquals("uniform_linear_for_n", change.properties().get("range"));
        assertTrue(
                change.properties().get("target").contains("sbml:parameter[@id='n']"),
                change.properties().toString()
        );
        assertEquals("uniform_linear_for_n", change.properties().get("math"));
        assertEquals("false", change.properties().get("mathLossy"));

        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_TASK:task1")
                        && relationship.type().equals("HAS_SUBTASK")
                        && relationship.endEntityKey().equals("SEDML_TASK:task0")
        ), parsed.entityRelationships().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_TASK:task1")
                        && relationship.type().equals("HAS_RANGE")
                        && relationship.endEntityKey().equals("SEDML_RANGE:uniform_linear_for_n")
        ), parsed.entityRelationships().toString());
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_TASK:task1")
                        && relationship.type().equals("HAS_CHANGE")
                        && relationship.endEntityKey().equals("SEDML_CHANGE:setValue-0")
        ), parsed.entityRelationships().toString());
    }

    @Test
    void skipsRepeatedTaskSubTaskThatReferencesAMissingTask() {
        var parsed = new SedmlModelParser().parse(source, stream("""
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version4" level="1" version="4">
                  <listOfTasks>
                    <repeatedTask id="task1" range="r1">
                      <listOfRanges>
                        <vectorRange id="r1">
                          <value>1</value>
                          <value>2</value>
                        </vectorRange>
                      </listOfRanges>
                      <listOfSubTasks>
                        <subTask order="0" task="missing"/>
                      </listOfSubTasks>
                    </repeatedTask>
                  </listOfTasks>
                </sedML>
                """));

        assertEquals("1", parsed.properties().get("sedml.tasks"), parsed.entities().toString());
        assertEquals("1", parsed.properties().get("sedml.ranges"), parsed.entities().toString());
        assertFalse(parsed.properties().containsKey("sedml.changes"), parsed.properties().toString());
        // The dangling reference is recorded like every other reference in this parser; the graph
        // mapper then drops it because SEDML_TASK:missing is no entity. Nothing is thrown.
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.startEntityKey().equals("SEDML_TASK:task1")
                        && relationship.type().equals("HAS_SUBTASK")
                        && relationship.endEntityKey().equals("SEDML_TASK:missing")
        ), parsed.entityRelationships().toString());
        assertTrue(new ParsedModelGraphMapper().toGraph(parsed).relationships().stream().noneMatch(relationship ->
                relationship.type().equals("HAS_SUBTASK")
        ), "a subTask naming a missing task must not survive into the graph");
        var range = parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_RANGE)
                .findFirst()
                .orElseThrow();
        assertEquals("vectorRange", range.properties().get("rangeType"));
        assertEquals("", range.properties().get("name"));
        assertFalse(range.properties().containsKey("start"), range.properties().toString());
        assertFalse(taskEntity(parsed, "task1").properties().containsKey("resetModel"));
    }

    private ParsedModelEntity taskEntity(ParsedModel parsed, String id) {
        return parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_TASK)
                .filter(entity -> entity.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SEDML_TASK " + id + ": " + parsed.entities()));
    }

    private ParsedModel parseFixture(Path fixture) throws Exception {
        assertTrue(Files.isRegularFile(fixture), "missing fixture: " + fixture);
        try (var stream = Files.newInputStream(fixture)) {
            return new SedmlModelParser().parse(new ModelSource(new ModelReference(
                    fixture.getFileName().toString(),
                    fixture.toUri(),
                    ModelType.SEDML
            )), stream);
        }
    }

    private ParsedModel parseWithDocumentUri(String documentUri, String modelSource) {
        var scopedSource = new ModelSource(new ModelReference(
                "test.sedml",
                URI.create(documentUri),
                ModelType.SEDML
        ));
        return new SedmlModelParser().parse(scopedSource, stream("""
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version4" level="1" version="4">
                  <listOfModels>
                    <model id="model1" name="m" language="urn:sedml:language:sbml" source="%s"/>
                  </listOfModels>
                </sedML>
                """.formatted(modelSource)));
    }

    private ParsedModelEntity modelEntity(ParsedModel parsed) {
        return parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_MODELREFERENCE)
                .filter(entity -> entity.id().equals("model1"))
                .findFirst()
                .orElseThrow();
    }

    private String modelProperty(ParsedModel parsed, String name) {
        return modelEntity(parsed).properties().get(name);
    }

    private String mathProperty(ParsedModel parsed) {
        return parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_DATAGENERATOR)
                .filter(entity -> entity.id().equals("dg1"))
                .map(entity -> entity.properties().get("math"))
                .findFirst()
                .orElseThrow();
    }

    private String mathLossyProperty(ParsedModel parsed) {
        return parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_DATAGENERATOR)
                .filter(entity -> entity.id().equals("dg1"))
                .map(entity -> entity.properties().get("mathLossy"))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, String> curveProperties(ParsedModel parsed, String curveId) {
        return parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_CURVE)
                .filter(entity -> entity.id().equals(curveId))
                .map(ParsedModelEntity::properties)
                .findFirst()
                .orElseThrow();
    }

    private String sedmlWithCurves(String... curves) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version3" level="1" version="3">
                  <listOfModels>
                    <model id="model1" language="urn:sedml:language:sbml" source="model.xml"/>
                  </listOfModels>
                  <listOfSimulations>
                    <uniformTimeCourse id="sim1" initialTime="0" outputStartTime="0" outputEndTime="10" numberOfPoints="100">
                      <algorithm kisaoID="KISAO:0000019"/>
                    </uniformTimeCourse>
                  </listOfSimulations>
                  <listOfTasks>
                    <task id="task1" modelReference="model1" simulationReference="sim1"/>
                  </listOfTasks>
                  <listOfDataGenerators>
                    <dataGenerator id="dg1">
                      <listOfVariables>
                        <variable id="var1" taskReference="task1" symbol="urn:sedml:symbol:time"/>
                      </listOfVariables>
                      <math xmlns="http://www.w3.org/1998/Math/MathML"><ci>var1</ci></math>
                    </dataGenerator>
                  </listOfDataGenerators>
                  <listOfOutputs>
                    <plot2D id="plot1">
                      <listOfCurves>
                """ + String.join("\n", curves) + """
                      </listOfCurves>
                    </plot2D>
                  </listOfOutputs>
                </sedML>
                """;
    }

    private String sedmlWithMath(String mathContent) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version3" level="1" version="3">
                  <listOfModels>
                    <model id="model1" language="urn:sedml:language:sbml" source="model.xml"/>
                  </listOfModels>
                  <listOfDataGenerators>
                    <dataGenerator id="dg1">
                      <math xmlns="http://www.w3.org/1998/Math/MathML">
                """ + mathContent + """
                      </math>
                    </dataGenerator>
                  </listOfDataGenerators>
                </sedML>
                """;
    }

    private ByteArrayInputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private String sedml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version3" level="1" version="3">
                  <listOfModels>
                    <model id="model1" language="urn:sedml:language:sbml" source="model.xml"/>
                  </listOfModels>
                  <listOfSimulations>
                    <uniformTimeCourse id="sim1" initialTime="0" outputStartTime="0" outputEndTime="10" numberOfPoints="100">
                      <algorithm kisaoID="KISAO:0000019"/>
                    </uniformTimeCourse>
                  </listOfSimulations>
                  <listOfTasks>
                    <task id="task1" modelReference="model1" simulationReference="sim1"/>
                  </listOfTasks>
                  <listOfDataGenerators>
                    <dataGenerator id="dg1">
                      <listOfVariables>
                        <variable id="var1" taskReference="task1" target="/sbml:sbml/sbml:model" symbol="urn:sedml:symbol:time"/>
                      </listOfVariables>
                      <math xmlns="http://www.w3.org/1998/Math/MathML">
                        <ci>var1</ci>
                      </math>
                    </dataGenerator>
                  </listOfDataGenerators>
                  <listOfOutputs>
                    <plot2D id="plot1">
                      <listOfCurves>
                        <curve id="curve1" xDataReference="time" yDataReference="dg1" logX="false" logY="true"/>
                      </listOfCurves>
                    </plot2D>
                    <plot3D id="plot3d">
                      <listOfSurfaces>
                        <surface id="surface1" xDataReference="time" yDataReference="dg1" zDataReference="dg1"/>
                      </listOfSurfaces>
                    </plot3D>
                  </listOfOutputs>
                </sedML>
                """;
    }
}

package org.masymos.core.extract;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelType;
import org.w3c.dom.Element;

public final class SedmlModelParser {
    public ParsedModel parse(ModelSource source, InputStream stream) {
        if (source.reference().modelType() != ModelType.SEDML) {
            throw new IllegalArgumentException("SED-ML parser requires a SED-ML model source");
        }

        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            var document = factory.newDocumentBuilder().parse(stream);
            var sedml = document.getDocumentElement();
            if (sedml == null || !"sedML".equals(sedml.getLocalName())) {
                throw new IllegalArgumentException("SED-ML document does not contain a sedML root element");
            }

            var relationships = new ArrayList<ParsedEntityRelationship>();
            var entities = entities(sedml, source.reference().sourceUri(), relationships);
            var properties = properties(sedml, entities);
            return new ParsedModel(
                    source.reference(),
                    firstPresent(sedml.getAttribute("id"), source.reference().fileId()),
                    firstPresent(sedml.getAttribute("name"), source.reference().fileId()),
                    properties,
                    entities,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.copyOf(relationships)
            );
        } catch (Exception exception) {
            throw new ModelParseException("failed to parse SED-ML document: " + source.reference().sourceUri(), exception);
        }
    }

    private static List<ParsedModelEntity> entities(
            Element sedml,
            URI documentUri,
            List<ParsedEntityRelationship> relationships
    ) {
        var entities = new ArrayList<ParsedModelEntity>();
        var taskModelReferences = new LinkedHashMap<String, String>();
        var dataGeneratorModelReferences = new LinkedHashMap<String, String>();

        directChildren(firstChild(sedml, "listOfModels"), "model").forEach(model -> {
            var modelProperties = new LinkedHashMap<String, String>();
            modelProperties.put("id", requiredId(model));
            modelProperties.put("name", nullToEmpty(model.getAttribute("name")));
            var modelSource = nullToEmpty(model.getAttribute("source"));
            modelProperties.put("modelSource", modelSource);
            var resolvedSource = resolveModelSource(documentUri, modelSource);
            if (resolvedSource != null) {
                modelProperties.put("resolvedSource", resolvedSource);
            }
            modelProperties.put(
                    "modelChanged",
                    Boolean.toString(!directChildren(firstChild(model, "listOfChanges"), "changeAttribute").isEmpty())
            );
            var modelLanguage = nullToEmpty(model.getAttribute("language"));
            if (!modelLanguage.isBlank()) {
                modelProperties.put("language", modelLanguage);
            }
            entities.add(entity(
                    EntityType.SEDML_MODELREFERENCE,
                    requiredId(model),
                    model.getAttribute("name"),
                    modelProperties
            ));
        });

        directChildren(firstChild(sedml, "listOfSimulations"), "uniformTimeCourse").forEach(simulation -> {
            var algorithm = firstChild(simulation, "algorithm");
            entities.add(entity(
                    EntityType.SEDML_SIMULATION,
                    requiredId(simulation),
                    simulation.getAttribute("name"),
                    Map.of(
                            "id", requiredId(simulation),
                            "name", nullToEmpty(simulation.getAttribute("name")),
                            "simType", simulation.getLocalName(),
                            "simKisao", algorithm == null ? "" : nullToEmpty(algorithm.getAttribute("kisaoID")),
                            "initialTime", nullToEmpty(simulation.getAttribute("initialTime")),
                            "outputStartTime", nullToEmpty(simulation.getAttribute("outputStartTime")),
                            "outputEndTime", nullToEmpty(simulation.getAttribute("outputEndTime")),
                            "numberOfPoints", nullToEmpty(simulation.getAttribute("numberOfPoints"))
                    )
            ));
        });

        // SEDML-1: steadyState and oneStep are the other two SED-ML L1 V2-V4 simulation elements.
        // They carry no time attributes; oneStep has an optional "step". Everything else mirrors
        // the uniformTimeCourse properties so a client sees one property vocabulary.
        directChildren(firstChild(sedml, "listOfSimulations"), "steadyState").forEach(simulation -> {
            var algorithm = firstChild(simulation, "algorithm");
            entities.add(entity(
                    EntityType.SEDML_SIMULATION,
                    requiredId(simulation),
                    simulation.getAttribute("name"),
                    Map.of(
                            "id", requiredId(simulation),
                            "name", nullToEmpty(simulation.getAttribute("name")),
                            "simType", simulation.getLocalName(),
                            "simKisao", algorithm == null ? "" : nullToEmpty(algorithm.getAttribute("kisaoID"))
                    )
            ));
        });

        directChildren(firstChild(sedml, "listOfSimulations"), "oneStep").forEach(simulation -> {
            var algorithm = firstChild(simulation, "algorithm");
            var step = nullToEmpty(simulation.getAttribute("step"));
            var oneStepProperties = new LinkedHashMap<String, String>();
            oneStepProperties.put("id", requiredId(simulation));
            oneStepProperties.put("name", nullToEmpty(simulation.getAttribute("name")));
            oneStepProperties.put("simType", simulation.getLocalName());
            oneStepProperties.put("simKisao", algorithm == null ? "" : nullToEmpty(algorithm.getAttribute("kisaoID")));
            // Written only when present and non-blank, so absence means "no step attribute".
            if (!step.isBlank()) {
                oneStepProperties.put("step", step);
            }
            entities.add(entity(
                    EntityType.SEDML_SIMULATION,
                    requiredId(simulation),
                    simulation.getAttribute("name"),
                    oneStepProperties
            ));
        });

        directChildren(firstChild(sedml, "listOfTasks"), "task").forEach(task -> {
            var taskId = requiredId(task);
            var modelReference = nullToEmpty(task.getAttribute("modelReference"));
            var simulationReference = nullToEmpty(task.getAttribute("simulationReference"));
            taskModelReferences.put(taskId, modelReference);
            entities.add(entity(
                    EntityType.SEDML_TASK,
                    taskId,
                    task.getAttribute("name"),
                    Map.of(
                            "id", taskId,
                            "name", nullToEmpty(task.getAttribute("name")),
                            "modelReference", modelReference,
                            "simulationReference", simulationReference
                    )
            ));
            addReferenceRelationship(relationships, EntityType.SEDML_TASK, taskId, "REFERENCES_MODEL", EntityType.SEDML_MODELREFERENCE, modelReference);
            addReferenceRelationship(relationships, EntityType.SEDML_TASK, taskId, "REFERENCES_SIMULATION", EntityType.SEDML_SIMULATION, simulationReference);
            addReferenceRelationship(relationships, EntityType.SEDML_MODELREFERENCE, modelReference, "IS_REFERENCED_IN_TASK", EntityType.SEDML_TASK, taskId);
            addReferenceRelationship(relationships, EntityType.SEDML_SIMULATION, simulationReference, "IS_REFERENCED_IN_TASK", EntityType.SEDML_TASK, taskId);
            addReferenceRelationship(relationships, EntityType.SEDML_SIMULATION, simulationReference, "SIMULATES", EntityType.SEDML_MODELREFERENCE, modelReference);
            addReferenceRelationship(relationships, EntityType.SEDML_MODELREFERENCE, modelReference, "IS_SIMULATED", EntityType.SEDML_SIMULATION, simulationReference);
        });

        // SEDML-2: a repeatedTask is a task, so it lands in the same SEDML_TASK type. Its ranges and
        // setValue changes are entities of their own, attached with HAS_RANGE/HAS_CHANGE, and each
        // subTask becomes a HAS_SUBTASK edge to the task it runs. The repeated task's own range and
        // resetModel attributes are properties when present.
        directChildren(firstChild(sedml, "listOfTasks"), "repeatedTask").forEach(repeatedTask -> {
            var taskId = requiredId(repeatedTask);
            var modelReference = nullToEmpty(repeatedTask.getAttribute("modelReference"));
            var simulationReference = nullToEmpty(repeatedTask.getAttribute("simulationReference"));
            var repeatedTaskProperties = new LinkedHashMap<String, String>();
            repeatedTaskProperties.put("id", taskId);
            repeatedTaskProperties.put("name", nullToEmpty(repeatedTask.getAttribute("name")));
            repeatedTaskProperties.put("modelReference", modelReference);
            repeatedTaskProperties.put("simulationReference", simulationReference);
            repeatedTaskProperties.put("repeated", "true");
            var range = nullToEmpty(repeatedTask.getAttribute("range"));
            if (!range.isBlank()) {
                repeatedTaskProperties.put("range", range);
            }
            var resetModel = nullToEmpty(repeatedTask.getAttribute("resetModel"));
            if (!resetModel.isBlank()) {
                repeatedTaskProperties.put("resetModel", resetModel);
            }
            entities.add(entity(
                    EntityType.SEDML_TASK,
                    taskId,
                    repeatedTask.getAttribute("name"),
                    repeatedTaskProperties
            ));

            directChildren(firstChild(repeatedTask, "listOfRanges"), null).forEach(eachRange -> {
                var rangeId = requiredId(eachRange);
                var rangeProperties = new LinkedHashMap<String, String>();
                rangeProperties.put("id", rangeId);
                rangeProperties.put("name", nullToEmpty(eachRange.getAttribute("name")));
                rangeProperties.put("rangeType", nullToEmpty(eachRange.getLocalName()));
                presentAttribute(eachRange, "start", rangeProperties);
                presentAttribute(eachRange, "end", rangeProperties);
                presentAttribute(eachRange, "numberOfSteps", rangeProperties);
                presentAttribute(eachRange, "type", rangeProperties);
                presentAttribute(eachRange, "values", rangeProperties);
                entities.add(entity(EntityType.SEDML_RANGE, rangeId, eachRange.getAttribute("name"), rangeProperties));
                addReferenceRelationship(relationships, EntityType.SEDML_TASK, taskId, "HAS_RANGE", EntityType.SEDML_RANGE, rangeId);
            });

            var changeIndex = 0;
            for (var change : directChildren(firstChild(repeatedTask, "listOfChanges"), null)) {
                var changeId = firstPresent(change.getAttribute("id"), "setValue-" + changeIndex);
                changeIndex++;
                var changeProperties = new LinkedHashMap<String, String>();
                changeProperties.put("id", changeId);
                changeProperties.put("changeType", nullToEmpty(change.getLocalName()));
                presentAttribute(change, "target", changeProperties);
                presentAttribute(change, "modelReference", changeProperties);
                presentAttribute(change, "range", changeProperties);
                presentAttribute(change, "symbol", changeProperties);
                // The same renderer as data generators, so a change's math reads like theirs.
                mathRendering(change).ifPresent(rendering -> {
                    changeProperties.put("math", rendering.text());
                    changeProperties.put("mathLossy", rendering.lossy() ? "true" : "false");
                });
                entities.add(entity(EntityType.SEDML_CHANGE, changeId, change.getAttribute("name"), changeProperties));
                addReferenceRelationship(relationships, EntityType.SEDML_TASK, taskId, "HAS_CHANGE", EntityType.SEDML_CHANGE, changeId);
            }

            directChildren(firstChild(repeatedTask, "listOfSubTasks"), "subTask").forEach(subTask -> {
                addReferenceRelationship(
                        relationships,
                        EntityType.SEDML_TASK,
                        taskId,
                        "HAS_SUBTASK",
                        EntityType.SEDML_TASK,
                        nullToEmpty(subTask.getAttribute("task"))
                );
            });
        });

        directChildren(firstChild(sedml, "listOfDataGenerators"), "dataGenerator").forEach(dataGenerator -> {
            var dataGeneratorId = requiredId(dataGenerator);
            var math = mathRendering(dataGenerator);
            var dataGeneratorProperties = new HashMap<>(Map.of(
                    "id", dataGeneratorId,
                    "name", nullToEmpty(dataGenerator.getAttribute("name")),
                    "math", math.map(MathRendering::text).orElse("")
            ));
            // Always present when a math element exists, so absence means "no math element" and a
            // client never has to guess whether the rendering is complete.
            math.ifPresent(rendering ->
                    dataGeneratorProperties.put("mathLossy", rendering.lossy() ? "true" : "false"));
            entities.add(entity(
                    EntityType.SEDML_DATAGENERATOR,
                    dataGeneratorId,
                    dataGenerator.getAttribute("name"),
                    Map.copyOf(dataGeneratorProperties)
            ));

            directChildren(firstChild(dataGenerator, "listOfVariables"), "variable").forEach(variable -> {
                var variableId = requiredId(variable);
                var modelReference = taskModelReferences.get(variable.getAttribute("taskReference"));
                if (modelReference != null && !modelReference.isBlank()) {
                    dataGeneratorModelReferences.putIfAbsent(dataGeneratorId, modelReference);
                }
                entities.add(entity(
                        EntityType.SEDML_VARIABLE,
                        variableId,
                        variable.getAttribute("name"),
                        Map.of(
                                "id", variableId,
                                "name", nullToEmpty(variable.getAttribute("name")),
                                "target", nullToEmpty(variable.getAttribute("target")),
                                "reference", nullToEmpty(variable.getAttribute("taskReference")),
                                "symbol", nullToEmpty(variable.getAttribute("symbol")),
                                "targetType", targetType(variable.getAttribute("target"))
                        )
                ));
                addReferenceRelationship(relationships, EntityType.SEDML_DATAGENERATOR, dataGeneratorId, "HAS_VARIABLE", EntityType.SEDML_VARIABLE, variableId);
                addReferenceRelationship(relationships, EntityType.SEDML_VARIABLE, variableId, "BELONGS_TO", EntityType.SEDML_DATAGENERATOR, dataGeneratorId);
                addReferenceRelationship(
                        relationships,
                        EntityType.SEDML_MODELREFERENCE,
                        modelReference,
                        "USED_IN_DATAGENERATOR",
                        EntityType.SEDML_VARIABLE,
                        variableId
                );
                addReferenceRelationship(
                        relationships,
                        EntityType.SEDML_VARIABLE,
                        variableId,
                        "CALCULATES_MODEL",
                        EntityType.SEDML_MODELREFERENCE,
                        modelReference
                );
            });
        });

        directChildren(firstChild(sedml, "listOfOutputs"), null).forEach(output -> {
            var outputId = requiredId(output);
            entities.add(entity(
                    EntityType.SEDML_OUTPUT,
                    outputId,
                    output.getAttribute("name"),
                    Map.of(
                            "id", outputId,
                            "name", nullToEmpty(output.getAttribute("name")),
                            "outputType", output.getLocalName()
                    )
            ));
            directChildren(firstChild(output, "listOfCurves"), "curve").forEach(curve -> {
                var curveId = requiredId(curve);
                var curveProperties = new LinkedHashMap<String, String>();
                curveProperties.put("id", curveId);
                curveProperties.put("name", nullToEmpty(curve.getAttribute("name")));
                curveProperties.put("xData", nullToEmpty(curve.getAttribute("xDataReference")));
                curveProperties.put("yData", nullToEmpty(curve.getAttribute("yDataReference")));
                curveProperties.put("logX", nullToEmpty(curve.getAttribute("logX")));
                curveProperties.put("logY", nullToEmpty(curve.getAttribute("logY")));
                timeAxisMarkers(curve).forEach(curveProperties::put);
                entities.add(entity(
                        EntityType.SEDML_CURVE,
                        curveId,
                        curve.getAttribute("name"),
                        curveProperties
                ));
                addReferenceRelationship(relationships, EntityType.SEDML_OUTPUT, outputId, "HAS_CURVE", EntityType.SEDML_CURVE, curveId);
                addDataReferenceRelationships(
                        relationships,
                        EntityType.SEDML_CURVE,
                        curveId,
                        List.of(curve.getAttribute("xDataReference"), curve.getAttribute("yDataReference")),
                        dataGeneratorModelReferences
                );
            });
            directChildren(firstChild(output, "listOfSurfaces"), "surface").forEach(surface -> {
                var surfaceId = requiredId(surface);
                entities.add(entity(
                        EntityType.SEDML_SURFACE,
                        surfaceId,
                        surface.getAttribute("name"),
                        Map.of(
                                "id", surfaceId,
                                "name", nullToEmpty(surface.getAttribute("name")),
                                "xData", nullToEmpty(surface.getAttribute("xDataReference")),
                                "yData", nullToEmpty(surface.getAttribute("yDataReference")),
                                "zData", nullToEmpty(surface.getAttribute("zDataReference"))
                        )
                ));
                addReferenceRelationship(relationships, EntityType.SEDML_OUTPUT, outputId, "HAS_SURFACE", EntityType.SEDML_SURFACE, surfaceId);
                addDataReferenceRelationships(
                        relationships,
                        EntityType.SEDML_SURFACE,
                        surfaceId,
                        List.of(
                                surface.getAttribute("xDataReference"),
                                surface.getAttribute("yDataReference"),
                                surface.getAttribute("zDataReference")
                        ),
                        dataGeneratorModelReferences
                );
            });
            directChildren(firstChild(output, "listOfDataSets"), "dataSet").forEach(dataSet -> {
                var dataSetId = requiredId(dataSet);
                entities.add(entity(
                        EntityType.SEDML_DATASET,
                        dataSetId,
                        dataSet.getAttribute("label"),
                        Map.of(
                                "id", dataSetId,
                                "name", nullToEmpty(dataSet.getAttribute("label")),
                                "dataLabel", nullToEmpty(dataSet.getAttribute("label")),
                                "dataReference", nullToEmpty(dataSet.getAttribute("dataReference"))
                        )
                ));
                addReferenceRelationship(relationships, EntityType.SEDML_OUTPUT, outputId, "HAS_DATASET", EntityType.SEDML_DATASET, dataSetId);
                addDataReferenceRelationships(
                        relationships,
                        EntityType.SEDML_DATASET,
                        dataSetId,
                        List.of(dataSet.getAttribute("dataReference")),
                        dataGeneratorModelReferences
                );
            });
        });

        return List.copyOf(entities);
    }

    private static LinkedHashMap<String, String> properties(Element sedml, List<ParsedModelEntity> entities) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("sedml.level", nullToEmpty(sedml.getAttribute("level")));
        properties.put("sedml.version", nullToEmpty(sedml.getAttribute("version")));
        var namespace = nullToEmpty(sedml.getNamespaceURI());
        if (!namespace.isBlank()) {
            properties.put("sedml.namespace", namespace);
        }
        properties.put("sedml.models", Long.toString(count(entities, EntityType.SEDML_MODELREFERENCE)));
        properties.put("sedml.simulations", Long.toString(count(entities, EntityType.SEDML_SIMULATION)));
        properties.put("sedml.tasks", Long.toString(count(entities, EntityType.SEDML_TASK)));
        // Written only when non-zero, so a document without repeated tasks reads exactly as before.
        var ranges = count(entities, EntityType.SEDML_RANGE);
        if (ranges > 0) {
            properties.put("sedml.ranges", Long.toString(ranges));
        }
        var changes = count(entities, EntityType.SEDML_CHANGE);
        if (changes > 0) {
            properties.put("sedml.changes", Long.toString(changes));
        }
        properties.put("sedml.dataGenerators", Long.toString(count(entities, EntityType.SEDML_DATAGENERATOR)));
        properties.put("sedml.outputs", Long.toString(count(entities, EntityType.SEDML_OUTPUT)));
        properties.put("sedml.variables", Long.toString(count(entities, EntityType.SEDML_VARIABLE)));
        properties.put("sedml.parameterTargets", Long.toString(entities.stream()
                .filter(entity -> entity.type() == EntityType.SEDML_VARIABLE)
                .filter(entity -> "parameter".equals(entity.properties().get("targetType")))
                .count()));
        return properties;
    }

    private static long count(List<ParsedModelEntity> entities, EntityType type) {
        return entities.stream().filter(entity -> entity.type() == type).count();
    }

    /**
     * Resolves a relative SED-ML {@code source} attribute against the URI of the document that
     * contains it. Archive URIs such as {@code file:///data/x.omex!/sedml/a.sedml} resolve to
     * {@code file:///data/x.omex!/models/a.sbml} because {@code !/} is an ordinary path segment.
     *
     * @return the resolved reference, or {@code null} when the raw value is absolute, blank,
     *     unparsable, or the document URI has no scheme to resolve against.
     */
    private static String resolveModelSource(URI documentUri, String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return null;
        }
        try {
            var reference = URI.create(rawSource);
            if (reference.isAbsolute()) {
                return null;
            }
            if (documentUri == null || !documentUri.isAbsolute()) {
                return null;
            }
            var resolved = documentUri.resolve(reference).toString();
            return resolved.isBlank() || resolved.equals(rawSource) ? null : resolved;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static ParsedModelEntity entity(EntityType type, String id, String name, Map<String, String> properties) {
        return new ParsedModelEntity(type, id, nullToEmpty(name), properties);
    }

    private static void addReferenceRelationship(
            List<ParsedEntityRelationship> relationships,
            EntityType startType,
            String startId,
            String type,
            EntityType endType,
            String endId
    ) {
        if (startId == null || startId.isBlank() || endId == null || endId.isBlank()) {
            return;
        }
        relationships.add(new ParsedEntityRelationship(startType + ":" + startId, type, endType + ":" + endId, Map.of()));
    }

    /**
     * The SED-ML curve axes whose reference is the literal {@code time} sentinel, as properties a
     * client can read: {@code xAxisIsTime} and/or {@code yAxisIsTime}, always the value {@code true}.
     *
     * <p>Absence is meaningful and unambiguous: every curve has both axis references, and a marker
     * is written for a reference that equals {@code time} ignoring case, nothing else — so a missing
     * {@code xAxisIsTime}/{@code yAxisIsTime} says "this axis is an ordinary reference (a data
     * generator id), not the simulation time axis". The test is textual, as legacy's was
     * ({@code SEDMLExtractor.java:195,204}): a reference that reads {@code time} is marked even when
     * the document also declares a data generator with that id. The text cannot tell the two apart and
     * legacy did not try to; SED-ML reserves the reference for a data generator id, so such a document
     * is already ambiguous.
     *
     * <p>{@code xData}/{@code yData} keep the reference text as written, whether or not it is the
     * sentinel; these markers only add the distinction legacy recorded by writing {@code time} into
     * {@code XDATA}/{@code YDATA} and resolving no data generator for it.
     */
    private static Map<String, String> timeAxisMarkers(Element curve) {
        var markers = new LinkedHashMap<String, String>();
        if (isTimeAxisReference(curve.getAttribute("xDataReference"))) {
            markers.put("xAxisIsTime", "true");
        }
        if (isTimeAxisReference(curve.getAttribute("yDataReference"))) {
            markers.put("yAxisIsTime", "true");
        }
        return markers;
    }

    private static boolean isTimeAxisReference(String reference) {
        return "time".equalsIgnoreCase(reference);
    }

    private static void addDataReferenceRelationships(
            List<ParsedEntityRelationship> relationships,
            EntityType startType,
            String startId,
            List<String> dataReferences,
            Map<String, String> dataGeneratorModelReferences
    ) {
        dataReferences.stream()
                .filter(reference -> reference != null && !reference.isBlank())
                .filter(reference -> !isTimeAxisReference(reference))
                .map(dataGeneratorModelReferences::get)
                .filter(modelReference -> modelReference != null && !modelReference.isBlank())
                .distinct()
                .forEach(modelReference -> addReferenceRelationship(
                        relationships,
                        startType,
                        startId,
                        "IS_ENTITY_OF",
                        EntityType.SEDML_MODELREFERENCE,
                        modelReference
                ));
    }

    /**
     * Copies an attribute into the property map only when it is present and non-blank, so a missing
     * property means the element did not carry that attribute.
     */
    private static void presentAttribute(Element element, String attribute, Map<String, String> properties) {
        var value = nullToEmpty(element.getAttribute(attribute));
        if (!value.isBlank()) {
            properties.put(attribute, value);
        }
    }

    private static Element firstChild(Element element, String localName) {
        if (element == null) {
            return null;
        }
        return directChildren(element, localName).stream().findFirst().orElse(null);
    }

    private static List<Element> directChildren(Element element, String localName) {
        if (element == null) {
            return List.of();
        }
        var children = new ArrayList<Element>();
        var nodes = element.getChildNodes();
        for (var index = 0; index < nodes.getLength(); index++) {
            var node = nodes.item(index);
            if (node instanceof Element child && (localName == null || localName.equals(child.getLocalName()))) {
                children.add(child);
            }
        }
        return children;
    }

    private static String requiredId(Element element) {
        var id = nullToEmpty(element.getAttribute("id"));
        if (!id.isBlank()) {
            return id;
        }
        return element.getLocalName() + "-" + Integer.toHexString(System.identityHashCode(element));
    }

    /**
     * Renders the data generator's {@code math} element in a single pass, so the stored text and the
     * {@code mathLossy} flag are always derived from the same traversal. Empty when the data
     * generator has no {@code math} element at all, which is the only case where no flag is written.
     */
    private static Optional<MathRendering> mathRendering(Element dataGenerator) {
        var math = firstChild(dataGenerator, "math");
        if (math == null) {
            return Optional.empty();
        }
        var builder = new StringBuilder();
        var lossy = false;
        for (var child : directChildren(math, null)) {
            var rendering = mathExpression(child);
            lossy = lossy || rendering.lossy();
            builder.append(rendering.text());
        }
        return Optional.of(new MathRendering(
                builder.toString().replaceAll("\\s+", " ").trim(),
                lossy
        ));
    }

    /**
     * Rendered MathML together with the information whether the rendering lost content.
     *
     * <p>Lossy rule, stated here so the representation is documented in one place:
     *
     * <ul>
     *   <li>{@code math}, {@code semantics}, {@code annotation}, {@code annotation-xml} and
     *       {@code degree} are markup only and never set the flag; {@code semantics} renders its
     *       first child that is not an annotation, so a duplicated presentation tree does not leak
     *       into the text.</li>
     *   <li>Every element that has no mapping sets the flag and keeps its flattened text as a
     *       fallback, so nothing silently disappears from the property.</li>
     *   <li>A mapped operator or named constant renders as its symbol and does <em>not</em> set the
     *       flag: the operator is preserved and its operands come from the surrounding
     *       {@code apply}, so nothing is lost. An element with no mapping renders as its own name
     *       so the expression stays readable, and does set the flag.</li>
     *   <li>An empty expression renders as the empty string without the flag.</li>
     * </ul>
     */
    private record MathRendering(String text, boolean lossy) {
        static final MathRendering EMPTY = new MathRendering("", false);

        static MathRendering of(String text) {
            return new MathRendering(text, false);
        }

        static MathRendering lossy(String text) {
            return new MathRendering(text, true);
        }
    }

    private static MathRendering mathExpression(Element element) {
        var localName = nullToEmpty(element.getLocalName());
        var constant = mathConstant(localName);
        if (!constant.isEmpty()) {
            return MathRendering.of(constant);
        }
        return switch (localName) {
            case "ci", "cn" -> MathRendering.of(element.getTextContent().trim());
            case "apply" -> mathApply(element);
            case "math", "semantics" -> mathTransparent(element);
            case "annotation", "annotation-xml" -> MathRendering.EMPTY;
            case "piecewise" -> mathPiecewise(element);
            case "lambda" -> mathLambda(element);
            case "bvar" -> MathRendering.of(mathBandVar(element));
            case "degree" -> mathTransparent(element);
            default -> mathUnknown(element, localName);
        };
    }

    /** Renders the concatenated children of a markup-only element such as {@code semantics}. */
    private static MathRendering mathTransparent(Element element) {
        var parts = new ArrayList<String>();
        var lossy = false;
        for (var child : directChildren(element, null)) {
            var childLocalName = nullToEmpty(child.getLocalName());
            if ("annotation".equals(childLocalName) || "annotation-xml".equals(childLocalName)) {
                continue;
            }
            var rendering = mathExpression(child);
            lossy = lossy || rendering.lossy();
            if (!rendering.text().isBlank()) {
                parts.add(rendering.text());
            }
        }
        return new MathRendering(String.join(" ", parts), lossy);
    }

    /**
     * Unknown MathML content must not silently disappear: a mapped operator renders as its symbol,
     * everything else recurses into the children first and falls back to the raw text content and is
     * flagged as lossy.
     */
    private static MathRendering mathUnknown(Element element, String localName) {
        var operator = mathOperator(localName);
        if (!operator.isEmpty()) {
            return MathRendering.of(operator);
        }
        var nested = new StringBuilder();
        var lossy = true;
        for (var child : directChildren(element, null)) {
            var rendering = mathExpression(child);
            lossy = lossy || rendering.lossy();
            nested.append(' ').append(rendering.text());
        }
        var text = nested.toString().trim();
        return new MathRendering(text.isEmpty() ? element.getTextContent().trim() : text, lossy);
    }

    private static MathRendering mathApply(Element apply) {
        var parts = new ArrayList<String>();
        var lossy = false;
        for (var child : directChildren(apply, null)) {
            var localName = nullToEmpty(child.getLocalName());
            var rendering = "bvar".equals(localName)
                    ? MathRendering.of("(binding " + mathBandVar(child) + ")")
                    : mathExpression(child);
            lossy = lossy || rendering.lossy();
            if (!rendering.text().isBlank()) {
                parts.add(rendering.text());
            }
        }
        return new MathRendering(parts.isEmpty() ? "" : " ( " + String.join(" ", parts) + " )", lossy);
    }

    /** One {@code bvar} may carry several bound variables separated by {@code ci} operands. */
    private static String mathBandVar(Element bandVar) {
        var names = new ArrayList<String>();
        for (var child : directChildren(bandVar, null)) {
            if (!"ci".equals(nullToEmpty(child.getLocalName()))) {
                continue;
            }
            for (var part : child.getTextContent().trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    names.add(part);
                }
            }
        }
        return String.join(" ", names);
    }

    /**
     * {@code piecewise} keeps the guard together with its value: {@code (if guard value)}.
     *
     * <p>{@code piece} lists the <em>value</em> first and the guard second — {@code <piece><cn>0</cn>
     * <ci>negative</ci></piece>} means "0 if negative" — so the two operands are reordered when the
     * case is rendered. Keeping them in document order would swap the value and its condition, which
     * would be a wrong expression rather than a lossy one, and the flag could not express that.
     */
    private static MathRendering mathPiecewise(Element piecewise) {
        var cases = new ArrayList<String>();
        var lossy = false;
        for (var child : directChildren(piecewise, null)) {
            var localName = nullToEmpty(child.getLocalName());
            if ("piece".equals(localName)) {
                var operands = new ArrayList<String>();
                for (var operand : directChildren(child, null)) {
                    var rendering = mathExpression(operand);
                    lossy = lossy || rendering.lossy();
                    if (!rendering.text().isBlank()) {
                        operands.add(rendering.text());
                    }
                }
                var value = operands.isEmpty() ? "" : operands.get(0);
                var guard = operands.size() > 1 ? operands.get(1) : "";
                cases.add(guard.isEmpty() ? "(if " + value + ")" : "(if " + guard + " " + value + ")");
            } else if ("otherwise".equals(localName)) {
                var rendering = mathTransparent(child);
                lossy = lossy || rendering.lossy();
                cases.add("(otherwise " + rendering.text() + ")");
            } else {
                var rendering = mathUnknown(child, localName);
                lossy = lossy || rendering.lossy();
                cases.add(rendering.text());
            }
        }
        return new MathRendering(cases.isEmpty() ? "" : " ( " + String.join(" ", cases) + " )", lossy);
    }

    /**
     * {@code lambda} renders its bound variables first, then the body; every child that is not a
     * {@code bvar} is body content so a nested {@code lambda} still renders as such rather than
     * being flattened.
     */
    private static MathRendering mathLambda(Element lambda) {
        var names = new ArrayList<String>();
        var body = new ArrayList<String>();
        var lossy = false;
        for (var child : directChildren(lambda, null)) {
            if ("bvar".equals(nullToEmpty(child.getLocalName()))) {
                var bound = mathBandVar(child);
                if (!bound.isEmpty()) {
                    names.add(bound);
                }
                continue;
            }
            var rendering = mathExpression(child);
            lossy = lossy || rendering.lossy();
            if (!rendering.text().isBlank()) {
                body.add(rendering.text());
            }
        }
        var text = body.isEmpty()
                ? ""
                : "(lambda " + (names.isEmpty() ? "" : String.join(" ", names) + " ") + String.join(" ", body) + ")";
        return new MathRendering(text, lossy);
    }

    private static String mathOperator(String localName) {
        return switch (localName) {
            case "plus" -> "+";
            case "minus" -> "-";
            case "times" -> "*";
            case "divide" -> "/";
            case "power" -> "^";
            case "eq" -> "=";
            case "lt" -> "<";
            case "gt" -> ">";
            case "leq" -> "<=";
            case "geq" -> ">=";
            case "neq" -> "!=";
            case "and" -> "and";
            case "or" -> "or";
            case "not" -> "not";
            case "xor" -> "xor";
            case "abs" -> "abs";
            case "exp" -> "exp";
            case "ln" -> "ln";
            case "log" -> "log";
            case "floor" -> "floor";
            case "ceiling" -> "ceiling";
            case "sin" -> "sin";
            case "cos" -> "cos";
            case "tan" -> "tan";
            case "arcsin" -> "arcsin";
            case "arccos" -> "arccos";
            case "arctan" -> "arctan";
            case "sinh" -> "sinh";
            case "cosh" -> "cosh";
            case "tanh" -> "tanh";
            case "root" -> "root";
            case "nthroot" -> "nthroot";
            case "sum" -> "sum";
            case "product" -> "product";
            case "int" -> "int";
            case "diff" -> "diff";
            default -> "";
        };
    }

    /** Named MathML constants are a complete rendering; their element name is not a symbol. */
    private static String mathConstant(String localName) {
        return switch (localName) {
            case "pi" -> "pi";
            case "exponentiale" -> "exponentiale";
            case "infinity" -> "infinity";
            case "notanumber" -> "notanumber";
            case "true" -> "true";
            case "false" -> "false";
            default -> "";
        };
    }

    private static String targetType(String target) {
        var normalized = nullToEmpty(target);
        if (normalized.contains("listOfSpecies") || normalized.contains(":species[")) {
            return "species";
        }
        if (normalized.contains("listOfParameters") || normalized.contains(":parameter[")) {
            return "parameter";
        }
        if (normalized.contains("listOfCompartments") || normalized.contains(":compartment[")) {
            return "compartment";
        }
        if (normalized.contains("cellml:variable")) {
            return "cellmlVariable";
        }
        return normalized.isBlank() ? "" : "unknown";
    }

    private static String firstPresent(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? nullToEmpty(fallback) : preferred;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

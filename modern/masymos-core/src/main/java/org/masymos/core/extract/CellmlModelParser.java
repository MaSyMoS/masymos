package org.masymos.core.extract;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelType;
import org.masymos.core.units.CanonicalUnit;
import org.masymos.core.units.UnitCatalogue;
import org.masymos.core.units.UnitDialect;
import org.masymos.core.units.UnitEntityBuilder;
import org.masymos.core.units.UnitExpression;
import org.masymos.core.units.UnitResolver;
import org.masymos.core.units.UnitTerm;
import org.w3c.dom.Element;

public final class CellmlModelParser {
    private static final String CMETA_NAMESPACE = "http://www.cellml.org/metadata/1.0#";
    private static final String XLINK_NAMESPACE = "http://www.w3.org/1999/xlink";

    private final CellmlExternalCmetaResolver externalCmetaResolver;
    private final CellmlImportResolver importResolver;

    public CellmlModelParser() {
        this(CellmlExternalCmetaResolver.NONE, CellmlImportResolver.NONE);
    }

    public CellmlModelParser(CellmlExternalCmetaResolver externalCmetaResolver) {
        this(externalCmetaResolver, CellmlImportResolver.NONE);
    }

    public CellmlModelParser(CellmlExternalCmetaResolver externalCmetaResolver, CellmlImportResolver importResolver) {
        this.externalCmetaResolver = externalCmetaResolver;
        this.importResolver = importResolver;
    }

    public ParsedModel parse(ModelSource source, InputStream stream) {
        if (source.reference().modelType() != ModelType.CELLML) {
            throw new IllegalArgumentException("CellML parser requires a CellML model source");
        }

        try {
            var factory = hardenedFactory();

            var document = factory.newDocumentBuilder().parse(stream);
            var model = document.getDocumentElement();
            if (model == null || !"model".equals(model.getLocalName())) {
                throw new IllegalArgumentException("CellML document does not contain a model root element");
            }
            var namespace = nullToEmpty(model.getNamespaceURI());
            if (versionFromNamespace(namespace).isEmpty()) {
                throw new IllegalArgumentException("CellML document root is not in a CellML namespace: " + namespace);
            }

            var variableIdsByName = new HashMap<String, VariableInfo>();
            var entityKeysByCmetaId = new HashMap<String, String>();
            var relationships = new ArrayList<ParsedEntityRelationship>();
            var units = Units.of(model, importResolver, source);
            var entities = new ArrayList<>(entities(model, variableIdsByName, entityKeysByCmetaId, relationships, units));
            entities.addAll(units.entities());
            relationships.addAll(units.relationships());
            var imports = imports(model, source, relationships, units);
            entities.addAll(imports.entities());
            var connectionRelationships = connections(model, variableIdsByName, connectionStyle(model));
            relationships.addAll(connectionRelationships);
            var rdfDescriptions = rdfDescriptions(model);
            var externalCmeta = externalCmetaResolver.resolve(source);
            var annotations = annotations(model, rdfDescriptions, entityKeysByCmetaId, externalCmeta);
            var externalAnnotations = externalAnnotations(externalCmeta);
            var publications = publications(rdfDescriptions, externalCmeta);
            var properties = new LinkedHashMap<String, String>();
            // CORP7-7: an external @@cmeta that yielded an annotation is a fact about the document
            // that the graph cannot otherwise record - the annotation is indistinguishable from one
            // the model carried itself. The corpus lane reads this key to measure the resolved path
            // instead of inferring it, so it is written only when the resolver's answer actually
            // contributed: a resolver that returned an all-null PMR skeleton, and the default NONE,
            // leave it absent and stay distinguishable from an enrichment.
            if (!externalAnnotations.isEmpty()) {
                properties.put("cellml.externalCmeta", "read");
            }
            // Components stay local: an imported component is its own entity type, so the COMPONENT
            // filter below only sees local components. Imported *variables* keep the VARIABLE type,
            // so cellml.variables also counts them once a resolver has resolved the import; with the
            // default resolver (NONE) the count is what it always was.
            properties.put("cellml.components", Long.toString(entities.stream()
                    .filter(entity -> entity.type() == EntityType.COMPONENT)
                    .count()));
            properties.put("cellml.variables", Long.toString(entities.stream()
                    .filter(entity -> entity.type() == EntityType.VARIABLE)
                    .count()));
            properties.put("cellml.reactions", Long.toString(entities.stream()
                    .filter(entity -> entity.type() == EntityType.CELLML_REACTION)
                    .count()));
            properties.put("cellml.connections", Integer.toString(connectionRelationships.size()));
            properties.put("cellml.annotations", Integer.toString(annotations.size()));
            properties.put("cellml.publications", Integer.toString(publications.size()));
            var declaredVersion = nullToEmpty(model.getAttribute("version"));
            // Real CellML 2.0 files omit the version attribute and identify themselves only through the
            // namespace (libcellml's Ohara_Rudy_2011.cellml does exactly that), so fall back to it.
            putIfPresent(properties, "cellml.version",
                    declaredVersion.isBlank() ? versionFromNamespace(model.getNamespaceURI()) : declaredVersion);
            putIfPresent(properties, "cellml.namespace", nullToEmpty(model.getNamespaceURI()));
            var unitsCount = directChildren(model, "units").size();
            if (unitsCount > 0) {
                properties.put("cellml.units", Integer.toString(unitsCount));
            }
            if (imports.importCount() > 0) {
                properties.put("cellml.imports", Integer.toString(imports.importCount()));
                if (imports.importedComponentCount() > 0) {
                    properties.put("cellml.importedComponents", Integer.toString(imports.importedComponentCount()));
                }
                if (imports.importedUnits() > 0) {
                    properties.put("cellml.importedUnits", Integer.toString(imports.importedUnits()));
                }
            }

            var modelName = nullToEmpty(model.getAttribute("name"));
            var modelId = firstPresent(cmetaId(model), modelName);
            return new ParsedModel(
                    source.reference(),
                    modelId,
                    modelName,
                    properties,
                    entities,
                    List.of(),
                    annotations,
                    publications,
                    List.copyOf(relationships)
            );
        } catch (Exception exception) {
            throw new ModelParseException("failed to parse CellML model: " + source.reference().sourceUri(), exception);
        }
    }

    private static List<ParsedModelEntity> entities(
            Element model,
            Map<String, VariableInfo> variableIdsByName,
            Map<String, String> entityKeysByCmetaId,
            List<ParsedEntityRelationship> relationships,
            Units units
    ) {
        var entities = new ArrayList<ParsedModelEntity>();
        var components = model.getElementsByTagNameNS("*", "component");
        for (var index = 0; index < components.getLength(); index++) {
            var component = (Element) components.item(index);
            if (isImportedComponentElement(component)) {
                // CML-2: <component component_ref=...> inside <import> is an imported component,
                // modelled by imports() with its own entity type. Treating it as a local COMPONENT
                // is what made it merge with a local component of the same name.
                continue;
            }
            var componentName = nullToEmpty(component.getAttribute("name"));
            var componentId = firstPresent(cmetaId(component), componentName);
            putEntityKey(entityKeysByCmetaId, cmetaId(component), EntityType.COMPONENT, componentId);
            entities.add(new ParsedModelEntity(
                    EntityType.COMPONENT,
                    componentId,
                    componentName,
                    properties("id", componentId, "name", componentName)
            ));

            var variables = component.getElementsByTagNameNS("*", "variable");
            for (var variableIndex = 0; variableIndex < variables.getLength(); variableIndex++) {
                var variable = (Element) variables.item(variableIndex);
                var variableName = nullToEmpty(variable.getAttribute("name"));
                var variableId = firstPresent(cmetaId(variable), componentName + "/" + variableName);
                putEntityKey(entityKeysByCmetaId, cmetaId(variable), EntityType.VARIABLE, variableId);
                variableIdsByName.put(variableLookupKey(componentName, variableName), new VariableInfo(
                        variableId,
                        nullToEmpty(variable.getAttribute("public_interface")),
                        nullToEmpty(variable.getAttribute("private_interface"))
                ));
                var properties = properties("id", variableId, "name", variableName);
                properties.put("component", componentName);
                properties.put("publicInterface", nullToEmpty(variable.getAttribute("public_interface")));
                properties.put("privateInterface", nullToEmpty(variable.getAttribute("private_interface")));
                putIfPresent(properties, "units", nullToEmpty(variable.getAttribute("units")));
                entities.add(new ParsedModelEntity(EntityType.VARIABLE, variableId, variableName, properties));
                var declaredUnits = nullToEmpty(variable.getAttribute("units"));
                units.referenced(componentName, declaredUnits)
                        .ifPresent(unit -> relationships.add(new ParsedEntityRelationship(
                                EntityType.VARIABLE + ":" + variableId,
                                "HAS_UNIT",
                                "UNIT:" + unit.key(),
                                Map.of("declaredName", declaredUnits))));
                relationships.add(new ParsedEntityRelationship(
                        EntityType.COMPONENT + ":" + componentId,
                        "HAS_VARIABLE",
                        EntityType.VARIABLE + ":" + variableId,
                        Map.of()
                ));
            }

            var reactions = component.getElementsByTagNameNS("*", "reaction");
            for (var reactionIndex = 0; reactionIndex < reactions.getLength(); reactionIndex++) {
                var reaction = (Element) reactions.item(reactionIndex);
                var reactionId = firstPresent(cmetaId(reaction), componentName + "/reaction-" + reactionIndex);
                putEntityKey(entityKeysByCmetaId, cmetaId(reaction), EntityType.CELLML_REACTION, reactionId);
                var reactionName = firstPresent(nullToEmpty(reaction.getAttribute("name")), reactionId);
                var properties = properties("id", reactionId, "name", reactionName);
                properties.put("component", componentName);
                properties.put("reversible", Boolean.toString(isTruthy(reaction.getAttribute("reversible"))));
                entities.add(new ParsedModelEntity(EntityType.CELLML_REACTION, reactionId, reactionName, properties));
                relationships.add(new ParsedEntityRelationship(
                        EntityType.COMPONENT + ":" + componentId,
                        "HAS_REACTION",
                        EntityType.CELLML_REACTION + ":" + reactionId,
                        Map.of()
                ));
                relationships.addAll(reactionParticipants(componentName, reactionId, reaction, variableIdsByName));
            }
        }
        return List.copyOf(entities);
    }

    /**
     * Models every {@code <import>} of the document (CML-2). Each import becomes one {@link
     * EntityType#CELLML_IMPORT} and each usable {@code <importedComponent>} one {@link
     * EntityType#CELLML_IMPORTED_COMPONENT} - a distinct type, so an imported component never merges
     * with a local component of the same name. Resolution runs once per (href, component_ref) pair,
     * so many imported components of one import cost one read of the target document.
     *
     * <p>Resolution is never silent: the import entity always carries {@code resolved}, and an
     * unresolved one carries a {@code resolution} reason from the closed vocabulary, which its
     * imported components repeat.
     *
     * <p>{@code <units>} children of an import are unit definitions (CML-1), not entities of this
     * walk; whether they resolved comes from {@code units}, and their number goes to
     * {@code cellml.importedUnits}.
     */
    private Imports imports(
            Element model,
            ModelSource source,
            List<ParsedEntityRelationship> relationships,
            Units units
    ) {
        var entities = new ArrayList<ParsedModelEntity>();
        var takenImportIds = new HashSet<String>();
        var resolutionByTarget = new HashMap<String, CellmlImportResolver.ImportTarget>();
        var importCount = 0;
        var importedComponentCount = 0;
        var importedUnits = 0;
        var importElements = directChildren(model, "import");
        for (var importIndex = 0; importIndex < importElements.size(); importIndex++) {
            var cellmlImport = importElements.get(importIndex);
            var href = nullToEmpty(cellmlImport.getAttributeNS(XLINK_NAMESPACE, "href")).trim();
            var baseId = href.isBlank() ? "import-" + importIndex : href;
            var importId = uniqueId(baseId, importIndex, takenImportIds);
            var importName = href.isBlank() ? "import-" + importIndex : href;
            var importProperties = properties("id", importId, "name", importName);
            putIfPresent(importProperties, "href", href);
            importCount++;
            importedUnits += directChildren(cellmlImport, "units").size();

            var importedComponents = new ArrayList<>(directChildren(cellmlImport, "importedComponent"));
            // CellML 2.0 names the element <importedComponent>; 1.0/1.1 use <component> with the same
            // component_ref attribute - and every real 1.1 fixture in this repo (GLUT2_BG.cellml) is
            // 1.1, so both names must be read or the import stays invisible.
            importedComponents.addAll(directChildren(cellmlImport, "component"));
            var importedComponentIds = new HashSet<String>();
            var resolvedComponents = new ArrayList<ParsedModelEntity>();
            var resolvedRelationships = new ArrayList<ParsedEntityRelationship>();
            String firstFailureReason = null;
            var anyResolved = false;
            var componentCount = 0;
            for (var componentIndex = 0; componentIndex < importedComponents.size(); componentIndex++) {
                var element = importedComponents.get(componentIndex);
                var componentRef = nullToEmpty(element.getAttribute("component_ref")).trim();
                if (componentRef.isBlank()) {
                    continue;
                }
                var target = resolutionByTarget.computeIfAbsent(
                        importId + "\u0000" + componentRef,
                        key -> importResolver.resolve(href, componentRef, source));
                var declaredName = nullToEmpty(element.getAttribute("name")).trim();
                var componentName = declaredName.isBlank() ? componentRef : declaredName;
                // Scoped by the import so an imported component and a local component of the same
                // name can never share an entity key; the plain alias stays in `name`.
                var componentId = uniqueId(importId + "#" + componentName, componentIndex, importedComponentIds);
                importedComponentCount++;
                componentCount++;

                var componentProperties = properties("id", componentId, "name", componentName);
                componentProperties.put("componentRef", componentRef);
                componentProperties.put("importId", importId);
                componentProperties.put("imported", "true");
                putIfPresent(componentProperties, "importedFrom", href);
                var reason = copyResolution(componentProperties, target);
                if (reason == null) {
                    anyResolved = true;
                } else if (firstFailureReason == null) {
                    firstFailureReason = reason;
                }
                resolvedComponents.add(new ParsedModelEntity(
                        EntityType.CELLML_IMPORTED_COMPONENT,
                        componentId,
                        componentName,
                        componentProperties
                ));
                resolvedRelationships.add(new ParsedEntityRelationship(
                        EntityType.CELLML_IMPORT + ":" + importId,
                        "HAS_IMPORTED_COMPONENT",
                        EntityType.CELLML_IMPORTED_COMPONENT + ":" + componentId,
                        Map.of()
                ));

                if (target instanceof CellmlImportResolver.Resolved resolved) {
                    addResolvedVariables(
                            href,
                            componentRef,
                            componentId,
                            resolved,
                            resolvedComponents,
                            resolvedRelationships
                    );
                }
            }

            // The import counts as resolved when at least one of its components or of its units did;
            // otherwise it repeats the first failure, so the reason survives on both levels. Units
            // are resolved by the unit layer (CML-1), components by this walk.
            if (units.resolvedUnitsImport(href)) {
                anyResolved = true;
            }
            if (anyResolved) {
                importProperties.put("resolved", "true");
            } else {
                importProperties.put("resolved", "false");
                importProperties.put("resolution", firstFailureReason != null
                        ? firstFailureReason
                        : (componentCount == 0 ? "no-imported-components" : "no-resolver"));
            }
            entities.add(new ParsedModelEntity(
                    EntityType.CELLML_IMPORT,
                    importId,
                    importName,
                    importProperties
            ));
            entities.addAll(resolvedComponents);
            relationships.addAll(resolvedRelationships);
        }
        return new Imports(List.copyOf(entities), importCount, importedComponentCount, importedUnits);
    }

    private static void addResolvedVariables(
            String href,
            String componentRef,
            String componentId,
            CellmlImportResolver.Resolved resolved,
            List<ParsedModelEntity> entities,
            List<ParsedEntityRelationship> relationships
    ) {
        var takenIds = new HashSet<String>();
        for (var index = 0; index < resolved.variables().size(); index++) {
            var variable = resolved.variables().get(index);
            if (variable.name().isBlank()) {
                continue;
            }
            var variableId = uniqueId(componentId + "/" + variable.name(), index, takenIds);
            var properties = properties("id", variableId, "name", variable.name());
            properties.put("component", componentId);
            properties.put("componentRef", componentRef);
            properties.put("publicInterface", variable.publicInterface());
            properties.put("privateInterface", variable.privateInterface());
            putIfPresent(properties, "units", variable.units());
            properties.put("imported", "true");
            properties.put("resolved", "true");
            putIfPresent(properties, "importedFrom", href);
            entities.add(new ParsedModelEntity(EntityType.VARIABLE, variableId, variable.name(), properties));
            relationships.add(new ParsedEntityRelationship(
                    EntityType.CELLML_IMPORTED_COMPONENT + ":" + componentId,
                    "HAS_VARIABLE",
                    EntityType.VARIABLE + ":" + variableId,
                    Map.of()
            ));
        }
    }

    /**
     * Writes {@code resolved} and, when unresolved, the closed-vocabulary {@code resolution} reason.
     *
     * @return the reason written, or {@code null} when the target was resolved
     */
    private static String copyResolution(
            Map<String, String> properties,
            CellmlImportResolver.ImportTarget target
    ) {
        if (target instanceof CellmlImportResolver.Resolved) {
            properties.put("resolved", "true");
            return null;
        }
        var reason = target instanceof CellmlImportResolver.Unresolved unresolved && !unresolved.reason().isBlank()
                ? unresolved.reason()
                : "no-resolver";
        properties.put("resolved", "false");
        properties.put("resolution", reason);
        return reason;
    }

    /**
     * Keeps entity ids unique inside one scope: the base id on first use, then {@code base#index},
     * then {@code base#index-n} for further collisions.
     */
    private static String uniqueId(String base, int index, Set<String> taken) {
        var id = takeIfFree(base, taken);
        if (id != null) {
            return id;
        }
        id = takeIfFree(base + "#" + index, taken);
        if (id != null) {
            return id;
        }
        for (var suffix = 2; ; suffix++) {
            id = takeIfFree(base + "#" + index + "-" + suffix, taken);
            if (id != null) {
                return id;
            }
        }
    }

    private static String takeIfFree(String candidate, Set<String> taken) {
        return taken.add(candidate) ? candidate : null;
    }

    private static boolean isImportedComponentElement(Element component) {
        return component.getParentNode() instanceof Element parent && "import".equals(parent.getLocalName());
    }

    private static DocumentBuilderFactory hardenedFactory() throws ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static List<ParsedEntityRelationship> reactionParticipants(
            String componentName,
            String reactionId,
            Element reaction,
            Map<String, VariableInfo> variableIdsByName
    ) {
        var relationships = new ArrayList<ParsedEntityRelationship>();
        for (var variableRef : directChildren(reaction, "variable_ref")) {
            var variableName = nullToEmpty(variableRef.getAttribute("variable"));
            var variableInfo = variableIdsByName.get(variableLookupKey(componentName, variableName));
            if (variableInfo == null) {
                continue;
            }
            for (var role : directChildren(variableRef, "role")) {
                var participantRole = participantRole(role.getAttribute("role"));
                if (participantRole != null) {
                    var properties = roleProperties(role);
                    relationships.add(new ParsedEntityRelationship(
                            EntityType.CELLML_REACTION + ":" + reactionId,
                            hasParticipantRelationship(participantRole),
                            EntityType.VARIABLE + ":" + variableInfo.id(),
                            properties
                    ));
                    relationships.add(new ParsedEntityRelationship(
                            EntityType.VARIABLE + ":" + variableInfo.id(),
                            isParticipantRelationship(participantRole),
                            EntityType.CELLML_REACTION + ":" + reactionId,
                            properties
                    ));
                }

                var deltaVariableName = nullToEmpty(role.getAttribute("delta_variable"));
                var deltaVariableInfo = variableIdsByName.get(variableLookupKey(componentName, deltaVariableName));
                if (deltaVariableInfo != null) {
                    var properties = roleProperties(role);
                    relationships.add(new ParsedEntityRelationship(
                            EntityType.CELLML_REACTION + ":" + reactionId,
                            "HAS_DELTA_VAR",
                            EntityType.VARIABLE + ":" + deltaVariableInfo.id(),
                            properties
                    ));
                    relationships.add(new ParsedEntityRelationship(
                            EntityType.VARIABLE + ":" + deltaVariableInfo.id(),
                            "IS_DELTA_VAR",
                            EntityType.CELLML_REACTION + ":" + reactionId,
                            properties
                    ));
                }
            }
        }
        return List.copyOf(relationships);
    }

    private static ParsedReactionParticipant.Role participantRole(String role) {
        return switch (nullToEmpty(role).toLowerCase()) {
            case "reactant" -> ParsedReactionParticipant.Role.REACTANT;
            case "product" -> ParsedReactionParticipant.Role.PRODUCT;
            case "modifier", "catalyst", "activator", "inhibitor" -> ParsedReactionParticipant.Role.MODIFIER;
            default -> null;
        };
    }

    private static Map<String, String> roleProperties(Element role) {
        var properties = new LinkedHashMap<String, String>();
        putIfPresent(properties, "role", nullToEmpty(role.getAttribute("role")));
        putIfPresent(properties, "direction", nullToEmpty(role.getAttribute("direction")));
        putIfPresent(properties, "stoichiometry", nullToEmpty(role.getAttribute("stoichiometry")));
        putIfPresent(properties, "deltaVariable", nullToEmpty(role.getAttribute("delta_variable")));
        return Map.copyOf(properties);
    }

    private static String hasParticipantRelationship(ParsedReactionParticipant.Role role) {
        return switch (role) {
            case REACTANT -> "HAS_REACTANT";
            case PRODUCT -> "HAS_PRODUCT";
            case MODIFIER -> "HAS_MODIFIER";
        };
    }

    private static String isParticipantRelationship(ParsedReactionParticipant.Role role) {
        return switch (role) {
            case REACTANT -> "IS_REACTANT";
            case PRODUCT -> "IS_PRODUCT";
            case MODIFIER -> "IS_MODIFIER";
        };
    }

    private static List<ParsedEntityRelationship> connections(
            Element model,
            Map<String, VariableInfo> variableIdsByName,
            ConnectionStyle connectionStyle
    ) {
        var relationships = new ArrayList<ParsedEntityRelationship>();
        var connections = model.getElementsByTagNameNS("*", "connection");
        for (var index = 0; index < connections.getLength(); index++) {
            var connection = (Element) connections.item(index);
            var components = connectionComponents(connection, connectionStyle);
            var component1 = components[0];
            var component2 = components[1];
            if (component1.isBlank() || component2.isBlank()) {
                continue;
            }
            var mappedVariables = connection.getElementsByTagNameNS("*", "map_variables");
            for (var variableIndex = 0; variableIndex < mappedVariables.getLength(); variableIndex++) {
                var mappedVariable = (Element) mappedVariables.item(variableIndex);
                var variable1 = nullToEmpty(mappedVariable.getAttribute("variable_1"));
                var variable2 = nullToEmpty(mappedVariable.getAttribute("variable_2"));
                var variableInfo1 = variableIdsByName.get(variableLookupKey(component1, variable1));
                var variableInfo2 = variableIdsByName.get(variableLookupKey(component2, variable2));
                if (variableInfo1 == null || variableInfo2 == null) {
                    continue;
                }
                var directedConnection = directedConnection(variableInfo1, variableInfo2);
                relationships.add(new ParsedEntityRelationship(
                        EntityType.VARIABLE + ":" + directedConnection.start().id(),
                        "IS_CONNECTED_TO",
                        EntityType.VARIABLE + ":" + directedConnection.end().id(),
                        Map.of(
                                "component1", component1,
                                "variable1", variable1,
                                "component2", component2,
                                "variable2", variable2,
                                "isPrivateConnection", Boolean.toString(directedConnection.privateConnection())
                        )
                ));
            }
        }
        return List.copyOf(relationships);
    }

    private static ConnectionStyle connectionStyle(Element model) {
        var version = nullToEmpty(model.getAttribute("version")).trim();
        if ("2.0".equals(version)) {
            return ConnectionStyle.ATTRIBUTE_ON_CONNECTION;
        }
        var namespace = nullToEmpty(model.getNamespaceURI());
        if (namespace.contains("/cellml/2.0#")) {
            return ConnectionStyle.ATTRIBUTE_ON_CONNECTION;
        }
        return ConnectionStyle.MAP_COMPONENTS;
    }

    /**
     * Resolves {@code component_1}/{@code component_2} for a connection using the style the document
     * declares. When that shape is absent the other shape is used as a safety net.
     */
    private static String[] connectionComponents(Element connection, ConnectionStyle connectionStyle) {
        var mapComponents = firstChild(connection, "map_components");
        var nested = mapComponents == null
                ? new String[] {"", ""}
                : new String[] {
                    nullToEmpty(mapComponents.getAttribute("component_1")),
                    nullToEmpty(mapComponents.getAttribute("component_2"))
                };
        var inline = new String[] {
            nullToEmpty(connection.getAttribute("component_1")),
            nullToEmpty(connection.getAttribute("component_2"))
        };
        var expected = connectionStyle == ConnectionStyle.ATTRIBUTE_ON_CONNECTION ? inline : nested;
        var fallback = connectionStyle == ConnectionStyle.ATTRIBUTE_ON_CONNECTION ? nested : inline;
        return expected[0].isBlank() || expected[1].isBlank() ? fallback : expected;
    }

    private static List<ParsedAnnotation> annotations(
            Element model,
            Map<String, Element> rdfDescriptions,
            Map<String, String> entityKeysByCmetaId,
            List<CellmlExternalCmeta> externalCmeta
    ) {
        var annotations = new ArrayList<ParsedAnnotation>();
        var modelDescription = firstPresentDescription(rdfDescriptions, "", "#" + cmetaId(model));
        var modelCreators = creators(modelDescription, rdfDescriptions);
        var modelProperties = annotationProperties(modelDescription, rdfDescriptions);
        var modelResources = resources(modelDescription, rdfDescriptions);
        if (!modelCreators.isEmpty() || !modelProperties.isEmpty() || !modelResources.isEmpty()) {
            annotations.add(new ParsedAnnotation("model", modelProperties, modelResources, modelCreators, List.of()));
        }

        entityKeysByCmetaId.forEach((cmetaId, entityKey) -> {
            var description = rdfDescriptions.get("#" + cmetaId);
            if (description == null) {
                return;
            }
            var properties = annotationProperties(description, rdfDescriptions);
            var resources = resources(description, rdfDescriptions);
            if (!properties.isEmpty() || !resources.isEmpty()) {
                annotations.add(new ParsedAnnotation(entityKey, properties, resources, List.of(), List.of()));
            }
        });
        annotations.addAll(externalAnnotations(externalCmeta));
        return List.copyOf(annotations);
    }

    private static List<ParsedPublication> publications(
            Map<String, Element> rdfDescriptions,
            List<CellmlExternalCmeta> externalCmeta
    ) {
        var publications = new LinkedHashMap<String, ParsedPublication>();
        for (var description : rdfDescriptions.values()) {
            for (var reference : directChildren(description, "reference")) {
                var referenceDescription = rdfDescriptions.get(resource(reference));
                if (referenceDescription == null) {
                    continue;
                }
                var article = rdfDescriptions.get(resource(firstChild(referenceDescription, "JournalArticle")));
                if (article == null) {
                    continue;
                }
                var pubmedId = textOfFirstChild(referenceDescription, "Pubmed_id");
                var journal = "";
                var journalDescription = rdfDescriptions.get(resource(firstChild(article, "Journal")));
                if (journalDescription != null) {
                    journal = textOfFirstChild(journalDescription, "title");
                }
                var publication = new ParsedPublication(
                        pubmedId.isBlank() ? "" : "https://identifiers.org/pubmed/" + pubmedId,
                        textOfFirstChild(article, "title"),
                        journal,
                        "",
                        "",
                        year(rdfDescriptions.get(resource(firstChild(article, "issued")))),
                        peopleFromSequence(rdfDescriptions.get(resource(firstChild(article, "creator"))), rdfDescriptions)
                );
                publications.putIfAbsent(firstPresent(publication.pubId(), publication.title()), publication);
            }
        }
        for (var cmeta : externalCmeta) {
            var publication = externalPublication(cmeta);
            if (publication != null) {
                publications.putIfAbsent(firstPresent(publication.pubId(), publication.title()), publication);
            }
        }
        return List.copyOf(publications.values());
    }

    /**
     * One annotation per external {@code @@cmeta} that carries something to write. An answer with no
     * annotatable field at all - PMR's all-null skeleton - contributes nothing, and the caller uses
     * that emptiness as the signal that the external document added nothing ({@code
     * cellml.externalCmeta}).
     *
     * <p>An annotation written here is indistinguishable from one the model carried itself - same
     * shape, same subject, an annotation that idempotently {@code HAS_ANNOTATION} - so it records its
     * origin as {@code provenance = cellml.externalCmeta} (decision 44). The property exists for a
     * reader of the graph, and for nothing else: it is not mirrored onto the model node and it is not
     * indexed, because the annotation full-text index covers {@code RESOURCE} nodes only and the model
     * index takes one aggregated {@code keywords} string per model, so a provenance marker can
     * neither add a hit nor move a score.
     */
    private static List<ParsedAnnotation> externalAnnotations(List<CellmlExternalCmeta> externalCmeta) {
        var annotations = new ArrayList<ParsedAnnotation>();
        for (var cmeta : externalCmeta) {
            if (!cmeta.carriesAnnotation()) {
                continue;
            }
            var properties = new LinkedHashMap<String, String>();
            putIfPresent(properties, "title", cmeta.title());
            // CML-3: the keywords are carried on the annotation, and ParsedModelGraphMapper also
            // aggregates them onto the model node, where modelFulltextV6 indexes them: the annotation
            // full-text index covers RESOURCE nodes only, so the annotation alone would not be found.
            putIfPresent(properties, "keywords", cmeta.keywordsText());
            var resources = new ArrayList<ParsedAnnotationResource>();
            if (!nullToEmpty(cmeta.citationId()).isBlank()) {
                resources.add(new ParsedAnnotationResource(cmeta.citationId(), "isDescribedBy"));
            }
            var creator = externalModelAuthor(cmeta);
            var creators = creator == null ? List.<ParsedPerson>of() : List.of(creator);
            if (!properties.isEmpty() || !resources.isEmpty() || !creators.isEmpty()) {
                properties.put("provenance", "cellml.externalCmeta");
                annotations.add(new ParsedAnnotation(
                        "model",
                        Map.copyOf(properties),
                        List.copyOf(resources),
                        creators,
                        List.of()
                ));
            }
        }
        return List.copyOf(annotations);
    }

    private static ParsedPublication externalPublication(CellmlExternalCmeta cmeta) {
        if (nullToEmpty(cmeta.citationTitle()).isBlank()) {
            return null;
        }
        return new ParsedPublication(
                nullToEmpty(cmeta.citationId()),
                cmeta.citationTitle(),
                nullToEmpty(cmeta.citationJournal()),
                "",
                "",
                "",
                externalPublicationAuthors(cmeta)
        );
    }

    private static ParsedPerson externalModelAuthor(CellmlExternalCmeta cmeta) {
        var author = nullToEmpty(cmeta.modelAuthor()).trim();
        if (author.isBlank() && nullToEmpty(cmeta.modelAuthorOrg()).isBlank()) {
            return null;
        }
        var names = author.split("\\s+", 2);
        if (names.length == 1) {
            return new ParsedPerson("", names[0], "", nullToEmpty(cmeta.modelAuthorOrg()));
        }
        return new ParsedPerson(names[0], names[1], "", nullToEmpty(cmeta.modelAuthorOrg()));
    }

    private static List<ParsedPerson> externalPublicationAuthors(CellmlExternalCmeta cmeta) {
        var authors = new ArrayList<ParsedPerson>();
        for (var author : cmeta.citationAuthors()) {
            if (author.isEmpty() || nullToEmpty(author.getFirst()).isBlank()) {
                continue;
            }
            var familyName = author.getFirst();
            var givenName = author.size() > 1 ? nullToEmpty(author.get(1)) : "";
            authors.add(new ParsedPerson(givenName, familyName, "", ""));
        }
        return List.copyOf(authors);
    }

    private static List<ParsedAnnotationResource> resources(Element description, Map<String, Element> rdfDescriptions) {
        if (description == null) {
            return List.of();
        }
        var resources = new ArrayList<ParsedAnnotationResource>();
        for (var reference : directChildren(description, "reference")) {
            var referenceDescription = rdfDescriptions.get(resource(reference));
            var pubmedId = textOfFirstChild(referenceDescription, "Pubmed_id");
            if (!pubmedId.isBlank()) {
                resources.add(new ParsedAnnotationResource("https://identifiers.org/pubmed/" + pubmedId, "isDescribedBy"));
            } else if (!resource(reference).isBlank()) {
                resources.add(new ParsedAnnotationResource(resource(reference), "isDescribedBy"));
            }
        }
        return List.copyOf(resources);
    }

    private static List<ParsedPerson> creators(Element description, Map<String, Element> rdfDescriptions) {
        if (description == null) {
            return List.of();
        }
        var creators = new ArrayList<ParsedPerson>();
        for (var creator : directChildren(description, "creator")) {
            creators.addAll(peopleFromResource(resource(creator), rdfDescriptions));
        }
        return List.copyOf(creators);
    }

    private static List<ParsedPerson> peopleFromResource(String resource, Map<String, Element> rdfDescriptions) {
        var description = rdfDescriptions.get(resource);
        if (description == null) {
            return List.of();
        }
        if ("Seq".equals(description.getLocalName())) {
            return peopleFromSequence(description, rdfDescriptions);
        }
        var person = person(description, rdfDescriptions);
        return person == null ? List.of() : List.of(person);
    }

    private static List<ParsedPerson> peopleFromSequence(Element sequence, Map<String, Element> rdfDescriptions) {
        if (sequence == null) {
            return List.of();
        }
        var people = new ArrayList<ParsedPerson>();
        for (var item : directChildren(sequence, "li")) {
            var person = person(rdfDescriptions.get(resource(item)), rdfDescriptions);
            if (person != null) {
                people.add(person);
            }
        }
        return List.copyOf(people);
    }

    private static ParsedPerson person(Element personDescription, Map<String, Element> rdfDescriptions) {
        if (personDescription == null) {
            return null;
        }
        var nameDescription = rdfDescriptions.get(resource(firstChild(personDescription, "N")));
        if (nameDescription == null) {
            nameDescription = firstChild(personDescription, "N");
        }
        var givenName = textOfFirstChild(nameDescription, "Given");
        var familyName = textOfFirstChild(nameDescription, "Family");
        var emailDescription = rdfDescriptions.get(resource(firstChild(personDescription, "EMAIL")));
        var organizationDescription = rdfDescriptions.get(resource(firstChild(personDescription, "ORG")));
        var organization = organization(organizationDescription);
        var email = firstPresent(textOfFirstChild(emailDescription, "value"), textOfFirstChild(personDescription, "EMAIL"));
        if (givenName.isBlank() && familyName.isBlank() && email.isBlank() && organization.isBlank()) {
            return null;
        }
        return new ParsedPerson(givenName, familyName, email, organization);
    }

    private static String organization(Element organizationDescription) {
        var parts = List.of(
                textOfFirstChild(organizationDescription, "Orgname"),
                textOfFirstChild(organizationDescription, "Orgunit")
        ).stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        return String.join("; ", parts);
    }

    private static Map<String, String> annotationProperties(Element description, Map<String, Element> rdfDescriptions) {
        if (description == null) {
            return Map.of();
        }
        var properties = new LinkedHashMap<String, String>();
        putIfPresent(properties, "title", textOfFirstChild(description, "title"));
        putIfPresent(properties, "publisher", textOfFirstChild(description, "publisher"));
        putIfPresent(properties, "created", w3cdtf(rdfDescriptions.get(resource(firstChild(description, "created")))));
        putIfPresent(properties, "modified", w3cdtf(rdfDescriptions.get(resource(firstChild(description, "modified")))));
        var comment = rdfDescriptions.get(resource(firstChild(description, "comment")));
        putIfPresent(properties, "comment", textOfFirstChild(comment, "value"));
        return Map.copyOf(properties);
    }

    private static Map<String, Element> rdfDescriptions(Element model) {
        var descriptions = new LinkedHashMap<String, Element>();
        var elements = model.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description");
        for (var index = 0; index < elements.getLength(); index++) {
            var description = (Element) elements.item(index);
            descriptions.putIfAbsent(resource(description), description);
        }
        var sequences = model.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Seq");
        for (var index = 0; index < sequences.getLength(); index++) {
            var sequence = (Element) sequences.item(index);
            descriptions.putIfAbsent(resource(sequence), sequence);
        }
        return Map.copyOf(descriptions);
    }

    private static Element firstPresentDescription(Map<String, Element> descriptions, String... keys) {
        for (var key : keys) {
            var description = descriptions.get(key);
            if (description != null) {
                return description;
            }
        }
        return null;
    }

    private static Element firstChild(Element element, String localName) {
        if (element == null) {
            return null;
        }
        var children = element.getChildNodes();
        for (var index = 0; index < children.getLength(); index++) {
            var child = children.item(index);
            if (child instanceof Element childElement && localName.equals(childElement.getLocalName())) {
                return childElement;
            }
        }
        return null;
    }

    private static List<Element> directChildren(Element element, String localName) {
        if (element == null) {
            return List.of();
        }
        var children = new ArrayList<Element>();
        var nodes = element.getChildNodes();
        for (var index = 0; index < nodes.getLength(); index++) {
            var node = nodes.item(index);
            if (node instanceof Element child && localName.equals(child.getLocalName())) {
                children.add(child);
            }
        }
        return List.copyOf(children);
    }

    private static String textOfFirstChild(Element element, String localName) {
        var child = firstChild(element, localName);
        return child == null ? "" : nullToEmpty(child.getTextContent()).trim();
    }

    private static String w3cdtf(Element element) {
        return textOfFirstChild(element, "W3CDTF");
    }

    private static String year(Element element) {
        var value = w3cdtf(element);
        if (value.length() >= 4 && value.substring(0, 4).chars().allMatch(Character::isDigit)) {
            return value.substring(0, 4);
        }
        return value;
    }

    private static LinkedHashMap<String, String> properties(String firstKey, String firstValue, String secondKey, String secondValue) {
        var properties = new LinkedHashMap<String, String>();
        properties.put(firstKey, firstValue);
        properties.put(secondKey, secondValue);
        return properties;
    }

    private static String cmetaId(Element element) {
        var value = element.getAttributeNS(CMETA_NAMESPACE, "id");
        if (value == null || value.isBlank()) {
            value = element.getAttribute("cmeta:id");
        }
        return nullToEmpty(value);
    }

    private static String resource(Element element) {
        if (element == null) {
            return "";
        }
        var value = element.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "resource");
        if (!value.isBlank()) {
            return value;
        }
        return element.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "about");
    }

    private static void putEntityKey(Map<String, String> entityKeysByCmetaId, String cmetaId, EntityType entityType, String entityId) {
        if (!cmetaId.isBlank()) {
            entityKeysByCmetaId.put(cmetaId, entityType + ":" + entityId);
        }
    }

    private static void putIfPresent(Map<String, String> properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.put(key, value.trim());
        }
    }

    /**
     * Extracts the version from a CellML namespace such as {@code http://www.cellml.org/cellml/2.0#}.
     * Returns an empty string for anything that is not a plain dotted version.
     */
    private static String versionFromNamespace(String namespaceUri) {
        var namespace = nullToEmpty(namespaceUri);
        var marker = "/cellml/";
        var index = namespace.indexOf(marker);
        if (index < 0) {
            return "";
        }
        var version = namespace.substring(index + marker.length());
        if (version.endsWith("#")) {
            version = version.substring(0, version.length() - 1);
        }
        return version.matches("\\d+(\\.\\d+)*") ? version : "";
    }

    private static String firstPresent(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? nullToEmpty(fallback) : preferred;
    }

    private static String variableLookupKey(String componentName, String variableName) {
        return componentName + "/" + variableName;
    }

    /**
     * One model-level {@code units} declaration of an arbitrary CellML document, reduced in that
     * document's own scope - the entry point {@link FileCellmlImportResolver} uses for an imported
     * unit (CML-1). Empty when the document does not declare it, or when its own imports are needed
     * to reduce it: resolution depth stays one.
     */
    static Optional<CanonicalUnit> declaredUnits(Element model, String name) {
        return Units.declaredUnits(model, name);
    }

    private static DirectedConnection directedConnection(VariableInfo variable1, VariableInfo variable2) {
        if (isIn(variable1.privateInterface())) {
            return new DirectedConnection(variable2, variable1, true);
        }
        if (isIn(variable2.privateInterface())) {
            return new DirectedConnection(variable1, variable2, true);
        }
        if (isIn(variable1.publicInterface())) {
            return new DirectedConnection(variable2, variable1, false);
        }
        return new DirectedConnection(variable1, variable2, false);
    }

    private static boolean isIn(String value) {
        return "in".equalsIgnoreCase(value);
    }

    private static boolean isTruthy(String value) {
        return "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * The unit layer of a CellML document (CML-1). Every {@code units} element the document
     * declares - model-level, component-local in 1.x, and imported through an
     * {@code <import><units>} the resolver could read - becomes a {@code UNIT} entity; every
     * variable's {@code units} attribute a {@code HAS_UNIT} edge; every named component a
     * {@code COMPOSED_OF} edge. The built-in and declared units a declaration reduces to are
     * emitted too, so the first model that names a centimetre creates the metre.
     *
     * <p>Name lookup follows the specification (design 4.3): the component's own units first, then
     * the model's, then imported units, then the built-in table. A reference no declaration, no
     * import and no table answers becomes a {@code kind=unresolved} node rather than vanishing, and
     * an imported unit that did not resolve keeps its {@code href}, {@code units_ref} and a
     * {@code resolution} reason there.
     */
    private static final class Units {
        private final Element model;
        private final UnitDialect dialect;
        private final UnitCatalogue catalogue = UnitCatalogue.builtIns();
        private final UnitResolver resolver = new UnitResolver(catalogue);
        private final List<Declaration> declarations = new ArrayList<>();
        private final Map<String, CanonicalUnit> imported = new LinkedHashMap<>();
        private final List<UnresolvedUnitsImport> unresolvedImports = new ArrayList<>();
        private final Set<String> resolvedImportHrefs = new HashSet<>();
        private final Map<String, CanonicalUnit> resolved = new HashMap<>();
        private final Set<String> resolving = new HashSet<>();
        private final Map<String, CanonicalUnit> known = new LinkedHashMap<>();
        private List<CanonicalUnit> units;

        private Units(Element model) {
            this.model = model;
            this.dialect = "2.0".equals(versionFromNamespace(model.getNamespaceURI()))
                    ? UnitDialect.CELLML_20
                    : UnitDialect.CELLML_10_11;
            directChildren(model, "units").forEach(element -> declarations.add(
                    new Declaration("", nullToEmpty(element.getAttribute("name")), element)));
            for (var component : directChildren(model, "component")) {
                if (isImportedComponentElement(component)) {
                    continue;
                }
                var componentName = nullToEmpty(component.getAttribute("name"));
                directChildren(component, "units").forEach(element -> declarations.add(
                        new Declaration(componentName, nullToEmpty(element.getAttribute("name")), element)));
            }
        }

        /** The document and the units of its imports, resolved by {@code importResolver}. */
        static Units of(Element model, CellmlImportResolver importResolver, ModelSource source) {
            var units = new Units(model);
            units.importUnits(importResolver, source);
            return units;
        }

        /**
         * Reduces one model-level {@code units} declaration of an arbitrary CellML document, for an
         * import resolver that has the target at hand. Depth stays one: the target's own imports are
         * not followed, so a target unit defined from one of its imports reports as missing.
         */
        static Optional<CanonicalUnit> declaredUnits(Element model, String name) {
            if (model == null || name == null || name.isBlank()) {
                return Optional.empty();
            }
            var document = new Units(model);
            var declaration = document.declaration("", name.trim());
            if (declaration == null) {
                return Optional.empty();
            }
            var unit = document.resolve(declaration);
            return "unresolved".equals(unit.kind()) ? Optional.empty() : Optional.of(unit);
        }

        private void importUnits(CellmlImportResolver importResolver, ModelSource source) {
            for (var importElement : directChildren(model, "import")) {
                var href = nullToEmpty(importElement.getAttributeNS(XLINK_NAMESPACE, "href")).trim();
                for (var unitsElement : directChildren(importElement, "units")) {
                    var unitsRef = nullToEmpty(unitsElement.getAttribute("units_ref")).trim();
                    if (unitsRef.isBlank()) {
                        continue;
                    }
                    var declaredName = nullToEmpty(unitsElement.getAttribute("name")).trim();
                    var localName = declaredName.isBlank() ? unitsRef : declaredName;
                    var target = importResolver.resolveUnits(href, unitsRef, source);
                    if (target instanceof CellmlImportResolver.ResolvedUnits resolvedUnits) {
                        imported.putIfAbsent(localName, renamed(resolvedUnits.unit(), localName));
                        resolvedImportHrefs.add(href);
                        continue;
                    }
                    var reason = target instanceof CellmlImportResolver.Unresolved failed
                                    && !failed.reason().isBlank()
                            ? failed.reason()
                            : "no-resolver";
                    unresolvedImports.add(new UnresolvedUnitsImport(href, unitsRef, reason));
                }
            }
        }

        /** The import counts as resolved when at least one of its units resolved. */
        boolean resolvedUnitsImport(String href) {
            return resolvedImportHrefs.contains(href);
        }

        List<ParsedModelEntity> entities() {
            var entities = new ArrayList<>(UnitEntityBuilder.entities(all()));
            for (var failure : unresolvedImports) {
                entities.add(unresolvedImportEntity(failure));
            }
            return List.copyOf(entities);
        }

        List<ParsedEntityRelationship> relationships() {
            return UnitEntityBuilder.composition(all());
        }

        /** The unit a variable in this component means, when the document defines it at all. */
        Optional<CanonicalUnit> referenced(String component, String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            var declaration = declaration(component, name);
            if (declaration != null) {
                return Optional.of(resolve(declaration));
            }
            var importedUnit = imported.get(name);
            if (importedUnit != null) {
                known.putIfAbsent(importedUnit.key(), importedUnit);
                return Optional.of(importedUnit);
            }
            return builtIn(name);
        }

        private List<CanonicalUnit> all() {
            if (units != null) {
                return units;
            }
            var collected = new ArrayList<CanonicalUnit>();
            var seen = new HashSet<String>();
            declarations.forEach(declaration -> addWithClosure(resolve(declaration), collected, seen));
            imported.values().forEach(unit -> addWithClosure(unit, collected, seen));
            variables().forEach(unit -> addWithClosure(unit, collected, seen));
            units = List.copyOf(collected);
            return units;
        }

        private List<CanonicalUnit> variables() {
            var referenced = new ArrayList<CanonicalUnit>();
            for (var component : directChildren(model, "component")) {
                if (isImportedComponentElement(component)) {
                    continue;
                }
                var componentName = nullToEmpty(component.getAttribute("name"));
                for (var variable : directChildren(component, "variable")) {
                    referenced(componentName, nullToEmpty(variable.getAttribute("units")))
                            .ifPresent(referenced::add);
                }
            }
            return referenced;
        }

        private void addWithClosure(CanonicalUnit unit, List<CanonicalUnit> collected, Set<String> seen) {
            if (!seen.add(unit.key())) {
                return;
            }
            collected.add(unit);
            known.putIfAbsent(unit.key(), unit);
            for (var component : unit.components()) {
                var referenced = canonicalByKey(component.key());
                if (referenced != null) {
                    addWithClosure(referenced, collected, seen);
                }
            }
        }

        private CanonicalUnit canonicalByKey(String key) {
            var unit = known.get(key);
            if (unit != null) {
                return unit;
            }
            var builtIn = catalogue.byKey(key, dialect).orElse(null);
            if (builtIn != null) {
                known.put(key, builtIn);
            }
            return builtIn;
        }

        private CanonicalUnit resolve(Declaration declaration) {
            var scope = declaration.scope() + "/" + declaration.name();
            var memo = resolved.get(scope);
            if (memo != null) {
                return memo;
            }
            if (!resolving.add(scope)) {
                return unresolved(declaration.name());
            }
            try {
                var unit = isDeclaredBase(declaration) ? declaredBase(declaration.name()) : composed(declaration);
                resolved.put(scope, unit);
                known.putIfAbsent(unit.key(), unit);
                return unit;
            } finally {
                resolving.remove(scope);
            }
        }

        private static boolean isDeclaredBase(Declaration declaration) {
            return isTruthy(declaration.element().getAttribute("base_units"))
                    || directChildren(declaration.element(), "unit").isEmpty();
        }

        private CanonicalUnit composed(Declaration declaration) {
            var visible = visible(declaration);
            var terms = new ArrayList<UnitTerm>();
            var offset = "";
            for (var unit : directChildren(declaration.element(), "unit")) {
                var reference = nullToEmpty(unit.getAttribute("units"));
                var target = visible.get(reference);
                if (target == null) {
                    target = imported.get(reference);
                }
                if (target == null) {
                    target = builtIn(reference).orElse(null);
                }
                if (target == null) {
                    return unresolved(declaration.name());
                }
                known.putIfAbsent(target.key(), target);
                terms.add(new UnitTerm(reference, attribute(unit, "exponent", "1"), multiplierOf(unit)));
                var declaredOffset = nullToEmpty(unit.getAttribute("offset"));
                if (!declaredOffset.isBlank() && offset.isBlank()) {
                    offset = declaredOffset;
                }
            }
            // Imported units are in scope for a composed declaration too, so the resolver must see
            // them next to the document's own ones; without this a model-level unit built from an
            // imported one would be reported unresolved.
            var scope = new LinkedHashMap<>(visible);
            imported.forEach(scope::putIfAbsent);
            try {
                return resolver.resolve(new UnitExpression(declaration.name(), terms, "1", offset), dialect, scope);
            } catch (IllegalArgumentException notResolvable) {
                return unresolved(declaration.name());
            }
        }

        private Map<String, CanonicalUnit> visible(Declaration declaration) {
            var visible = new LinkedHashMap<String, CanonicalUnit>();
            for (var candidate : declarations) {
                if (candidate == declaration) {
                    continue;
                }
                if (!candidate.scope().isEmpty() && !candidate.scope().equals(declaration.scope())) {
                    continue;
                }
                visible.put(candidate.name(), resolve(candidate));
            }
            return visible;
        }

        private Declaration declaration(String component, String name) {
            Declaration modelLevel = null;
            for (var declaration : declarations) {
                if (!declaration.name().equals(name)) {
                    continue;
                }
                if (declaration.scope().equals(component)) {
                    return declaration;
                }
                if (declaration.scope().isEmpty()) {
                    modelLevel = declaration;
                }
            }
            return modelLevel;
        }

        private Optional<CanonicalUnit> builtIn(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            var unit = catalogue.builtIn(name, dialect).orElse(null);
            if (unit != null) {
                known.putIfAbsent(unit.key(), unit);
            }
            return Optional.ofNullable(unit);
        }

        private static CanonicalUnit declaredBase(String name) {
            return new CanonicalUnit(
                    "declared-base:" + name.toLowerCase(Locale.ROOT),
                    name,
                    List.of(name),
                    "declared-base",
                    name + "^1",
                    "1",
                    null,
                    List.of());
        }

        private static CanonicalUnit unresolved(String name) {
            return new CanonicalUnit(
                    "unresolved:" + name.toLowerCase(Locale.ROOT),
                    name,
                    List.of(name),
                    "unresolved",
                    name + "^1",
                    "1",
                    null,
                    List.of());
        }

        /**
         * The target document's own name for a unit becomes the importing document's name, because
         * that is the name its declarations and variables use. The key stays untouched, so the same
         * definition imported by two models is one node.
         */
        private static CanonicalUnit renamed(CanonicalUnit unit, String name) {
            if (name.equals(unit.display())) {
                return unit;
            }
            return new CanonicalUnit(
                    unit.key(),
                    name,
                    List.of(name),
                    unit.kind(),
                    unit.dimension(),
                    unit.multiplier(),
                    unit.offset(),
                    unit.components());
        }

        /**
         * An imported units declaration that did not resolve. Visible, never silently dropped: the
         * node names the target, the reference and the closed-vocabulary reason.
         */
        private static ParsedModelEntity unresolvedImportEntity(UnresolvedUnitsImport failure) {
            var key = "unresolved:" + failure.href() + "#" + failure.unitsRef();
            var properties = new LinkedHashMap<String, String>();
            properties.put("key", key);
            properties.put("display", failure.unitsRef());
            properties.put("names", failure.unitsRef());
            properties.put("kind", "unresolved");
            properties.put("href", failure.href());
            properties.put("units_ref", failure.unitsRef());
            properties.put("resolution", failure.reason());
            return new ParsedModelEntity(EntityType.UNIT, key, failure.unitsRef(), Map.copyOf(properties));
        }

        private static String multiplierOf(Element unit) {
            var scale = BigDecimal.ONE;
            var prefix = nullToEmpty(unit.getAttribute("prefix"));
            if (!prefix.isBlank()) {
                scale = scale.multiply(UnitCatalogue.prefixScale(prefix).orElse(BigDecimal.ONE));
            }
            var multiplier = nullToEmpty(unit.getAttribute("multiplier"));
            if (!multiplier.isBlank()) {
                scale = scale.multiply(new BigDecimal(multiplier));
            }
            return UnitCatalogue.normalise(scale);
        }

        private static String attribute(Element element, String name, String fallback) {
            var value = nullToEmpty(element.getAttribute(name));
            return value.isBlank() ? fallback : value.trim();
        }
    }

    /** One {@code units} element: its name and the component it was declared in (empty = model). */
    private record Declaration(String scope, String name, Element element) {
    }

    /** An {@code <import><units>} that did not resolve: target, reference and the reason. */
    private record UnresolvedUnitsImport(String href, String unitsRef, String reason) {
    }

    private record VariableInfo(String id, String publicInterface, String privateInterface) {
    }

    /** Entities and counts produced by the {@code <import>} walk, CML-2. */
    private record Imports(
            List<ParsedModelEntity> entities,
            int importCount,
            int importedComponentCount,
            int importedUnits
    ) {
    }

    /**
     * Declared CellML connection shape: 1.0/1.1 nest {@code component_1}/{@code component_2} in
     * {@code <map_components>}; 2.0 puts them on {@code <connection>}.
     */
    private enum ConnectionStyle {
        MAP_COMPONENTS,
        ATTRIBUTE_ON_CONNECTION
    }

    private record DirectedConnection(VariableInfo start, VariableInfo end, boolean privateConnection) {
    }
}

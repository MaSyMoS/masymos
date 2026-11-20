package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLStreamException;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.extract.ParsedReactionParticipant.Role;
import org.masymos.core.model.ModelType;
import org.masymos.core.units.CanonicalUnit;
import org.masymos.core.units.UnitCatalogue;
import org.masymos.core.units.UnitDialect;
import org.masymos.core.units.UnitEntityBuilder;
import org.masymos.core.units.UnitExpression;
import org.masymos.core.units.UnitResolver;
import org.masymos.core.units.UnitTerm;
import org.sbml.jsbml.Annotation;
import org.sbml.jsbml.CVTerm;
import org.sbml.jsbml.Creator;
import org.sbml.jsbml.Model;
import org.sbml.jsbml.NamedSBase;
import org.sbml.jsbml.Reaction;
import org.sbml.jsbml.Rule;
import org.sbml.jsbml.SBMLDocument;
import org.sbml.jsbml.SBMLReader;
import org.sbml.jsbml.SBase;
import org.sbml.jsbml.SimpleSpeciesReference;
import org.sbml.jsbml.SpeciesReference;
import org.sbml.jsbml.Unit;

public final class SbmlModelParser {
    public ParsedModel parse(ModelSource source, InputStream stream) {
        if (source.reference().modelType() != ModelType.SBML) {
            throw new IllegalArgumentException("SBML parser requires an SBML model source");
        }

        try {
            var content = stream.readAllBytes();
            var document = new SBMLReader().readSBMLFromStream(new ByteArrayInputStream(content));
            var model = document.getModel();
            if (model == null) {
                throw new IllegalArgumentException("SBML document does not contain a model");
            }

            var entities = entities(model);
            var level1Species = level1Version1SpeciesFallback(content, document, entities);
            entities.addAll(level1Species);

            var units = Units.of(document);
            entities.addAll(units.entities());
            var relationships = new ArrayList<ParsedEntityRelationship>();
            relationships.addAll(units.relationships());
            relationships.addAll(units.hasUnitEdges());

            var properties = properties(document, model);
            if (!level1Species.isEmpty()) {
                properties.put("sbml.species", Integer.toString(level1Species.size()));
            }

            return new ParsedModel(
                    source.reference(),
                    model.getId(),
                    model.getName(),
                    properties,
                    entities,
                    reactionParticipants(model),
                    annotations(model),
                    List.of(),
                    List.copyOf(relationships)
            );
        } catch (IOException | XMLStreamException exception) {
            throw new ModelParseException("failed to parse SBML model: " + source.reference().sourceUri(), exception);
        }
    }

    /**
     * JSBML 1.6.1 loads compartments, parameters and reactions of a Level 1 Version 1 document but
     * returns no species at all (verified against Level 1 Version 2, Level 2 and Level 3, which all
     * load species). Dropping them silently would hide exactly the oldest corpus, so the species list
     * is recovered from the document itself in that one case.
     */
    private static List<ParsedModelEntity> level1Version1SpeciesFallback(
            byte[] content,
            SBMLDocument document,
            List<ParsedModelEntity> entities
    ) {
        if (document.getLevel() != 1 || document.getVersion() != 1) {
            return List.of();
        }
        if (entities.stream().anyMatch(entity -> entity.type() == EntityType.SPECIES)) {
            return List.of();
        }

        var recovered = new ArrayList<ParsedModelEntity>();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            var speciesNodes = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content))
                    .getElementsByTagNameNS("*", "species");

            for (var index = 0; index < speciesNodes.getLength(); index++) {
                var species = (org.w3c.dom.Element) speciesNodes.item(index);
                var properties = new LinkedHashMap<String, String>();
                var id = species.getAttribute("name");
                properties.put("id", id);
                properties.put("name", id);
                properties.put("compartment", species.getAttribute("compartment"));
                if (species.hasAttribute("initialAmount")) {
                    properties.put("initialAmount", species.getAttribute("initialAmount"));
                }
                if (species.hasAttribute("boundaryCondition")) {
                    properties.put("boundaryCondition", species.getAttribute("boundaryCondition"));
                }
                recovered.add(new ParsedModelEntity(EntityType.SPECIES, id, id, properties));
            }
        } catch (Exception exception) {
            // The fallback is best effort; a failure must not break parsing of the document.
            return List.of();
        }
        return recovered;
    }

    private static LinkedHashMap<String, String> properties(SBMLDocument document, Model model) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("sbml.level", Integer.toString(document.getLevel()));
        properties.put("sbml.version", Integer.toString(document.getVersion()));
        properties.put("sbml.compartments", Long.toString(model.getNumCompartments()));
        properties.put("sbml.species", Long.toString(model.getNumSpecies()));
        properties.put("sbml.reactions", Long.toString(model.getNumReactions()));
        properties.put("sbml.parameters", Long.toString(model.getNumParameters()));
        properties.put("sbml.rules", Long.toString(model.getNumRules()));
        properties.put("sbml.events", Long.toString(model.getNumEvents()));
        properties.put("sbml.functions", Long.toString(model.getNumFunctionDefinitions()));
        if (model.isSetConversionFactor()) {
            // Level 3 only; absent in Level 1 and 2 documents.
            properties.put("sbml.conversionFactor", nullToEmpty(model.getConversionFactor()));
        }
        var localParameters = 0;
        for (var reaction : model.getListOfReactions()) {
            if (reaction.isSetKineticLaw()) {
                // Level 3 feature: parameters scoped to a single reaction's kinetic law.
                localParameters += reaction.getKineticLaw().getListOfLocalParameters().size();
            }
        }
        if (localParameters > 0) {
            properties.put("sbml.localParameters", Integer.toString(localParameters));
        }
        return properties;
    }

    private static ArrayList<ParsedModelEntity> entities(Model model) {
        var entities = new ArrayList<ParsedModelEntity>();

        model.getListOfCompartments().forEach(compartment -> {
            var properties = new LinkedHashMap<String, String>();
            properties.put("id", nullToEmpty(compartment.getId()));
            properties.put("name", nullToEmpty(compartment.getName()));
            if (compartment.isSetSize()) {
                properties.put("size", Double.toString(compartment.getSize()));
            }
            // Level 1 has no spatialDimensions attribute; from Level 2 on it defaults to 3 but is
            // only recorded when the document states it.
            if (compartment.isSetSpatialDimensions()) {
                properties.put("spatialDimensions", sbmlNumber(compartment.getSpatialDimensions()));
            }
            entities.add(entity(EntityType.COMPARTMENT, compartment, properties));
        });

        model.getListOfSpecies().forEach(species -> {
            var properties = new LinkedHashMap<String, String>();
            properties.put("id", nullToEmpty(species.getId()));
            properties.put("name", nullToEmpty(species.getName()));
            properties.put("compartment", nullToEmpty(species.getCompartment()));
            // Level 1 knows only initialAmount; initialConcentration, constant and
            // hasOnlySubstanceUnits appear from Level 2 on, so every flag is written only when set.
            if (species.isSetInitialAmount()) {
                properties.put("initialAmount", Double.toString(species.getInitialAmount()));
            }
            if (species.isSetInitialConcentration()) {
                properties.put("initialConcentration", Double.toString(species.getInitialConcentration()));
            }
            if (species.isSetBoundaryCondition()) {
                properties.put("boundaryCondition", Boolean.toString(species.getBoundaryCondition()));
            }
            if (species.isSetConstant()) {
                properties.put("constant", Boolean.toString(species.getConstant()));
            }
            if (species.isSetHasOnlySubstanceUnits()) {
                properties.put("hasOnlySubstanceUnits", Boolean.toString(species.getHasOnlySubstanceUnits()));
            }
            entities.add(entity(EntityType.SPECIES, species, properties));
        });

        model.getListOfReactions().forEach(reaction -> {
            var properties = new LinkedHashMap<String, String>();
            properties.put("id", nullToEmpty(reaction.getId()));
            properties.put("name", nullToEmpty(reaction.getName()));
            properties.put("compartment", nullToEmpty(reaction.getCompartment()));
            entities.add(entity(EntityType.REACTION, reaction, properties));
        });

        model.getListOfParameters().forEach(parameter -> entities.add(namedEntity(EntityType.PARAMETER, parameter)));
        for (var index = 0; index < model.getListOfRules().size(); index++) {
            var rule = model.getListOfRules().get(index);
            var id = ruleIdentifier(rule, index);
            entities.add(new ParsedModelEntity(
                    EntityType.RULE,
                    id,
                    nullToEmpty(rule.getName()),
                    Map.of("id", id, "name", nullToEmpty(rule.getName()))
            ));
        }
        model.getListOfFunctionDefinitions().forEach(function -> entities.add(namedEntity(EntityType.FUNCTION, function)));
        model.getListOfEvents().forEach(event -> entities.add(namedEntity(EntityType.EVENT, event)));

        return entities;
    }

    private static ArrayList<ParsedReactionParticipant> reactionParticipants(Model model) {
        var participants = new ArrayList<ParsedReactionParticipant>();
        model.getListOfReactions().forEach(reaction -> addReactionParticipants(participants, reaction));
        return participants;
    }

    private static ArrayList<ParsedAnnotation> annotations(Model model) {
        var annotations = new ArrayList<ParsedAnnotation>();
        addAnnotation(annotations, "model", model);
        model.getListOfCompartments().forEach(compartment -> addAnnotation(annotations, entityKey(EntityType.COMPARTMENT, compartment), compartment));
        model.getListOfSpecies().forEach(species -> addAnnotation(annotations, entityKey(EntityType.SPECIES, species), species));
        model.getListOfReactions().forEach(reaction -> addAnnotation(annotations, entityKey(EntityType.REACTION, reaction), reaction));
        model.getListOfParameters().forEach(parameter -> addAnnotation(annotations, entityKey(EntityType.PARAMETER, parameter), parameter));
        for (var index = 0; index < model.getListOfRules().size(); index++) {
            var rule = model.getListOfRules().get(index);
            addAnnotation(annotations, EntityType.RULE.name().toLowerCase() + ":" + ruleIdentifier(rule, index), rule);
        }
        model.getListOfFunctionDefinitions().forEach(function -> addAnnotation(annotations, entityKey(EntityType.FUNCTION, function), function));
        model.getListOfEvents().forEach(event -> addAnnotation(annotations, entityKey(EntityType.EVENT, event), event));
        return annotations;
    }

    private static void addAnnotation(ArrayList<ParsedAnnotation> annotations, String subjectKey, NamedSBase sbase) {
        addAnnotation(annotations, subjectKey, (SBase) sbase);
    }

    private static void addAnnotation(ArrayList<ParsedAnnotation> annotations, String subjectKey, SBase sbase) {
        var annotation = sbase.getAnnotation();
        var resources = resources(annotation);
        var creators = creators(annotation);
        var properties = annotationProperties(annotation);
        var sboTerms = sboTerms(sbase);
        if (!resources.isEmpty() || !creators.isEmpty() || !properties.isEmpty() || !sboTerms.isEmpty()) {
            annotations.add(new ParsedAnnotation(subjectKey, properties, resources, creators, sboTerms));
        }
    }

    private static List<ParsedAnnotationResource> resources(Annotation annotation) {
        if (annotation == null || annotation.isEmpty()) {
            return List.of();
        }
        var resources = new ArrayList<ParsedAnnotationResource>();
        for (CVTerm term : annotation.getListOfCVTerms()) {
            var qualifier = "";
            if (term.isBiologicalQualifier()) {
                qualifier = term.getBiologicalQualifierType().getElementNameEquivalent();
            } else if (term.isModelQualifier()) {
                qualifier = term.getModelQualifierType().getElementNameEquivalent();
            }
            for (String uri : term.getResources()) {
                resources.add(new ParsedAnnotationResource(uri, qualifier));
            }
        }
        return resources;
    }

    private static List<ParsedPerson> creators(Annotation annotation) {
        if (annotation == null || annotation.getHistory() == null || annotation.getHistory().getListOfCreators() == null) {
            return List.of();
        }
        var creators = new ArrayList<ParsedPerson>();
        for (Creator creator : annotation.getHistory().getListOfCreators()) {
            creators.add(new ParsedPerson(
                    nullToEmpty(creator.getGivenName()),
                    nullToEmpty(creator.getFamilyName()),
                    nullToEmpty(creator.getEmail()),
                    nullToEmpty(creator.getOrganization())
            ));
        }
        return creators;
    }

    private static Map<String, String> annotationProperties(Annotation annotation) {
        if (annotation == null || annotation.getHistory() == null) {
            return Map.of();
        }
        var properties = new LinkedHashMap<String, String>();
        var history = annotation.getHistory();
        if (history.isSetCreatedDate()) {
            properties.put("created", history.getCreatedDate().toString());
        }
        if (history.isSetModifiedDate()) {
            properties.put("modified", history.getModifiedDate().toString());
        }
        return properties;
    }

    private static List<String> sboTerms(SBase sbase) {
        if (!sbase.isSetSBOTerm()) {
            return List.of();
        }
        return List.of("urn:miriam:biomodels.sbo:" + sbase.getSBOTermID());
    }

    private static void addReactionParticipants(ArrayList<ParsedReactionParticipant> participants, Reaction reaction) {
        reaction.getListOfReactants().forEach(reference -> participants.add(participant(
                reaction.getId(),
                reference,
                Role.REACTANT
        )));
        reaction.getListOfProducts().forEach(reference -> participants.add(participant(
                reaction.getId(),
                reference,
                Role.PRODUCT
        )));
        reaction.getListOfModifiers().forEach(reference -> participants.add(participant(
                reaction.getId(),
                reference,
                Role.MODIFIER
        )));
    }

    private static ParsedReactionParticipant participant(String reactionId, SimpleSpeciesReference reference, Role role) {
        var properties = new LinkedHashMap<String, String>();
        if (reference instanceof SpeciesReference speciesReference && speciesReference.isSetStoichiometry()) {
            properties.put("stoichiometry", sbmlNumber(speciesReference.getStoichiometry()));
        }
        return new ParsedReactionParticipant(reactionId, reference.getSpecies(), role, properties);
    }

    /**
     * SBML serialises integral numbers without a fractional part; JSBML exposes them as doubles, so
     * "3" is stored rather than "3.0" while a genuine 0.5 stays exact.
     */
    /**
     * The unit layer of an SBML model (CML-1). A {@code unitDefinition} is a product of the 33
     * predefined kinds and never of another definition (L3V2 4.4.2), so this walk is one level deep.
     *
     * <p>Level 1 declares definitions by {@code name}, has no {@code multiplier}, and its kinds are
     * case-insensitive; its {@code substance}/{@code volume} definitions are ordinary definitions
     * whose {@code scale} is exactly what the model means, never a hidden default.
     */
    private static final class Units {
        private final Model model;
        private final int level;
        private final int version;
        private final UnitDialect dialect;
        private final UnitCatalogue catalogue = UnitCatalogue.builtIns();
        private final UnitResolver resolver = new UnitResolver(catalogue);
        private final Map<String, CanonicalUnit> declared = new LinkedHashMap<>();
        private final Map<String, CanonicalUnit> known = new LinkedHashMap<>();
        private final List<CanonicalUnit> units = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();

        private Units(SBMLDocument document) {
            this.model = document.getModel();
            this.level = document.getLevel();
            this.version = document.getVersion();
            this.dialect = level <= 1 ? UnitDialect.SBML_1 : level == 2 ? UnitDialect.SBML_2 : UnitDialect.SBML_3;
            collect();
        }

        static Units of(SBMLDocument document) {
            return new Units(document);
        }

        List<ParsedModelEntity> entities() {
            return UnitEntityBuilder.entities(units);
        }

        List<ParsedEntityRelationship> relationships() {
            return UnitEntityBuilder.composition(units);
        }

        /** One {@code HAS_UNIT} per declared reference; the model-level categories only add units. */
        List<ParsedEntityRelationship> hasUnitEdges() {
            var edges = new ArrayList<ParsedEntityRelationship>();
            model.getListOfSpecies()
                    .forEach(species -> edge(edges, EntityType.SPECIES, species.getId(), species.getSubstanceUnits()));
            model.getListOfCompartments()
                    .forEach(compartment -> edge(edges, EntityType.COMPARTMENT, compartment.getId(), compartment.getUnits()));
            model.getListOfParameters()
                    .forEach(parameter -> edge(edges, EntityType.PARAMETER, parameter.getId(), parameter.getUnits()));
            return edges;
        }

        private void collect() {
            for (var definition : model.getListOfUnitDefinitions()) {
                var name = definition.isSetId() ? definition.getId() : definition.getName();
                var terms = new ArrayList<UnitTerm>();
                var offset = "";
                for (var unit : definition.getListOfUnits()) {
                    terms.add(new UnitTerm(kindName(unit.getKind()), sbmlNumber(unit.getExponent()), multiplier(unit)));
                    var declaredOffset = offsetOf(unit);
                    if (!declaredOffset.isBlank() && offset.isBlank()) {
                        offset = declaredOffset;
                    }
                }
                var unit = resolve(name, terms, offset);
                declared.put(name, unit);
                add(unit);
            }
            model.getListOfSpecies().forEach(species -> reference(species.getSubstanceUnits()));
            model.getListOfCompartments().forEach(compartment -> reference(compartment.getUnits()));
            model.getListOfParameters().forEach(parameter -> reference(parameter.getUnits()));
            for (var reaction : model.getListOfReactions()) {
                if (!reaction.isSetKineticLaw()) {
                    continue;
                }
                var law = reaction.getKineticLaw();
                if (level >= 2) {
                    law.getListOfLocalParameters().forEach(parameter -> reference(parameter.getUnits()));
                } else {
                    law.getListOfParameters().forEach(parameter -> reference(parameter.getUnits()));
                }
            }
            if (level >= 2) {
                // Only a stated category is a reference; JSBML reports the default name for an
                // unset attribute, and materialising it would make every model "use mole".
                if (model.isSetSubstanceUnits()) {
                    reference(model.getSubstanceUnits());
                }
                if (model.isSetTimeUnits()) {
                    reference(model.getTimeUnits());
                }
                if (model.isSetExtentUnits()) {
                    reference(model.getExtentUnits());
                }
                if (model.isSetVolumeUnits()) {
                    reference(model.getVolumeUnits());
                }
                if (model.isSetAreaUnits()) {
                    reference(model.getAreaUnits());
                }
                if (model.isSetLengthUnits()) {
                    reference(model.getLengthUnits());
                }
            }
        }

        private void edge(List<ParsedEntityRelationship> edges, EntityType type, String id, String name) {
            var unit = reference(name);
            if (unit == null || id == null || id.isBlank()) {
                return;
            }
            edges.add(new ParsedEntityRelationship(
                    type + ":" + id, "HAS_UNIT", "UNIT:" + unit.key(), Map.of("declaredName", name)));
        }

        private CanonicalUnit reference(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            var unit = declared.get(name);
            if (unit == null) {
                unit = catalogue.builtIn(name, dialect).orElse(null);
            }
            if (unit == null) {
                unit = unresolved(name);
            }
            add(unit);
            return unit;
        }

        private void add(CanonicalUnit unit) {
            if (!seen.add(unit.key())) {
                return;
            }
            units.add(unit);
            known.putIfAbsent(unit.key(), unit);
            for (var component : unit.components()) {
                var referenced = known.get(component.key());
                if (referenced == null) {
                    referenced = catalogue.byKey(component.key(), dialect).orElse(null);
                }
                if (referenced != null) {
                    add(referenced);
                }
            }
        }

        private CanonicalUnit resolve(String name, List<UnitTerm> terms, String offset) {
            try {
                return resolver.resolve(new UnitExpression(name, terms, "1", offset), dialect, Map.of());
            } catch (IllegalArgumentException notResolvable) {
                return unresolved(name);
            }
        }

        private String multiplier(Unit unit) {
            var scale = UnitCatalogue.prefixScale(Integer.toString(unit.getScale())).orElse(BigDecimal.ONE);
            var multiplier = level >= 2 && unit.isSetMultiplier()
                    ? new BigDecimal(sbmlNumber(unit.getMultiplier()))
                    : BigDecimal.ONE;
            return UnitCatalogue.normalise(scale.multiply(multiplier));
        }

        /** {@code Unit/@offset} exists in Level 2 Version 1 only and was removed in 2.2. */
        private String offsetOf(Unit unit) {
            return level == 2 && version == 1 && unit.isSetOffset() ? sbmlNumber(unit.getOffset()) : "";
        }

        private static String kindName(Unit.Kind kind) {
            // JSBML's Kind.getName() is the human spelling ("degree Celsius" in L2V1); the enum
            // constant is the SBML kind identifier, which is what the catalogue tables key on.
            return kind.name().toLowerCase(Locale.ROOT);
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
    }

    private static String sbmlNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static ParsedModelEntity namedEntity(EntityType type, NamedSBase namedSBase) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("id", nullToEmpty(namedSBase.getId()));
        properties.put("name", nullToEmpty(namedSBase.getName()));
        return entity(type, namedSBase, properties);
    }

    private static ParsedModelEntity entity(EntityType type, NamedSBase namedSBase, LinkedHashMap<String, String> properties) {
        var id = nullToEmpty(namedSBase.getId());
        var name = nullToEmpty(namedSBase.getName());
        return new ParsedModelEntity(type, id, name, properties);
    }

    private static String entityKey(EntityType type, NamedSBase namedSBase) {
        return type.name().toLowerCase() + ":" + nullToEmpty(namedSBase.getId());
    }

    private static String ruleIdentifier(Rule rule, int index) {
        if (rule.isSetId() && !rule.getId().isBlank()) {
            return rule.getId();
        }
        if (rule.isSetMetaId() && !rule.getMetaId().isBlank()) {
            return rule.getMetaId();
        }
        return "rule-" + index;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

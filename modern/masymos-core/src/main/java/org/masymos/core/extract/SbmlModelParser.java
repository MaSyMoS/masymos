package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
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
import org.sbml.jsbml.ext.comp.CompConstants;
import org.sbml.jsbml.ext.comp.CompModelPlugin;
import org.sbml.jsbml.ext.comp.CompSBasePlugin;
import org.sbml.jsbml.ext.comp.ReplacedElement;
import org.sbml.jsbml.ext.comp.Submodel;
import org.sbml.jsbml.ext.fbc.Association;
import org.sbml.jsbml.ext.fbc.FBCConstants;
import org.sbml.jsbml.ext.fbc.FBCModelPlugin;
import org.sbml.jsbml.ext.fbc.FBCReactionPlugin;
import org.sbml.jsbml.ext.fbc.GeneProduct;
import org.sbml.jsbml.ext.fbc.GeneProductRef;
import org.sbml.jsbml.ext.fbc.LogicalOperator;

public final class SbmlModelParser {
    public ParsedModel parse(ModelSource source, InputStream stream) {
        if (source.reference().modelType() != ModelType.SBML) {
            throw new IllegalArgumentException("SBML parser requires an SBML model source");
        }

        try {
            var content = stream.readAllBytes();
            var namespace = nullToEmpty(UnsupportedSbmlNamespaces.rootNamespace(content));
            if (!namespace.startsWith(UnsupportedSbmlNamespaces.SBML_CORE_NAMESPACE_PREFIX)) {
                // JSBML answers a foreign root with an SBMLDocument that has no model, which would
                // otherwise surface as a bare IllegalArgumentException naming no source document.
                throw new ModelParseException(
                        "failed to parse SBML model: " + source.reference().sourceUri(),
                        new IllegalArgumentException("SBML document root is not in an SBML namespace: " + namespace));
            }
            var read = readDocument(content);
            var document = read.document();
            var model = document.getModel();
            if (model == null) {
                throw new IllegalArgumentException("SBML document does not contain a model");
            }

            var entities = entities(model);
            var level1Species = level1Version1SpeciesFallback(content, document, entities);
            entities.addAll(level1Species);

            var units = Units.of(document);
            entities.addAll(units.entities());
            var composition = new Composition(model);
            entities.addAll(composition.entities());
            var relationships = new ArrayList<ParsedEntityRelationship>();
            relationships.addAll(units.relationships());
            relationships.addAll(units.hasUnitEdges());
            relationships.addAll(geneProductAssociations(model));
            relationships.addAll(composition.relationships());

            var properties = properties(document, model);
            var droppedFbcNot = UnsupportedSbmlNamespaces.fbcNotCount(content);
            // OPN-2: `comp` is read by this parser now, so losing its subtrees to the strip retry is
            // still a loss of annotations, but not of the composition: it is no longer reported as a
            // dropped namespace. Every other namespace keeps its marker, and `fbc`'s `not` loss is
            // counted by `droppedFbcNot` exactly as before.
            var droppedNamespaces = read.droppedNamespaces().stream()
                    .filter(dropped -> !CompConstants.namespaces.contains(dropped))
                    .toList();
            if (!droppedNamespaces.isEmpty() || read.droppedNestedAnnotations() > 0 || droppedFbcNot > 0) {
                // CORP-9 (row CORP7-12): a reduced read has to say so, or the corpus lane counts a
                // stripped document as fully ingested.
                properties.put("sbml.reducedRead", "true");
                if (!droppedNamespaces.isEmpty()) {
                    properties.put("sbml.droppedNamespaces", String.join(" ", droppedNamespaces));
                }
                if (read.droppedNestedAnnotations() > 0) {
                    properties.put(
                            "sbml.droppedNestedAnnotations", Integer.toString(read.droppedNestedAnnotations()));
                }
                if (droppedFbcNot > 0) {
                    // FBC-4: `jsbml-fbc` 1.6.1 has no Not type, so every `fbc:not` operand is dropped and
                    // its reaction loses its edges; the count is what stops that loss being invisible.
                    properties.put("sbml.droppedFbcNot", Integer.toString(droppedFbcNot));
                }
            }
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
     * Reads the document with JSBML, retrying once without the content JSBML cannot parse.
     *
     * <p>Measured 2026-11-20 (`CORP-8`): JSBML 1.6.1 throws {@code ClassCastException: XMLNode cannot
     * be cast to Annotation} in two shapes - a core {@code <annotation>} hanging off an element in a
     * namespace with no reading parser (the CellDesigner `species` of the committed fixture), and a
     * whole {@code <annotation>} nested inside another annotation, which CellDesigner and COPASI write
     * as a second, tool-owned block inside the model's own (`CORP-13`). `CORP-6` cured the first shape
     * for `fbc` by putting the package jars on the classpath; where no package can supply the namespace,
     * or the offender is a nested annotation, the offending subtree is dropped instead
     * ({@link UnsupportedSbmlNamespaces}). The retry runs only on that one failure, so every document
     * JSBML already reads takes exactly the path it took before.</p>
     *
     * <p>The retry is reported: {@code droppedNamespaces} names every namespace the second read lost and
     * {@code droppedNestedAnnotations} counts the annotation blocks nested inside another annotation,
     * both empty/zero on the one-pass path. Before `CORP-9` (row `CORP7-12`) nothing recorded the retry,
     * so the corpus lane counted a stripped document as fully ingested and a reader could not tell that
     * annotations in a manufacturer namespace were never indexed (decision 43).</p>
     */
    private static Read readDocument(byte[] content) throws XMLStreamException {
        try {
            return new Read(
                    new SBMLReader().readSBMLFromStream(new ByteArrayInputStream(content)), List.of(), 0);
        } catch (ClassCastException failure) {
            var stripped = UnsupportedSbmlNamespaces.stripped(content);
            try {
                var document = new SBMLReader().readSBMLFromStream(new ByteArrayInputStream(stripped.document()));
                return new Read(document, stripped.namespaces(), stripped.nestedAnnotations());
            } catch (RuntimeException | XMLStreamException retry) {
                retry.addSuppressed(failure);
                throw retry;
            }
        }
    }

    /**
     * A read document and what the read had to drop: no namespaces and no nested annotation when JSBML
     * read the document in one pass, the stripped read's own accounting when it did not.
     */
    private record Read(SBMLDocument document, List<String> droppedNamespaces, int droppedNestedAnnotations) {
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
            var factory = hardenedFactory();
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

    /**
     * The one DOM factory the SBML parser uses - the Level 1 Version 1 species fallback is its only
     * DOM read. Nothing in a document may reach the network: a DOCTYPE is refused outright, entity
     * resolution is off, and the two JAXP access properties name no protocol at all.
     */
    static DocumentBuilderFactory hardenedFactory() throws ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory;
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
        geneProducts(model, entities);
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

    /**
     * The {@code fbc} gene products of a Level 3 document (decision 52). FBC v2 is a Level 3 package
     * and {@code jsbml-fbc} 1.6.1 implements that namespace only, so a Level 2 document is left alone
     * even where its root declares fbc. Measured 2026-11-24: the library binds its plugin on a Level 2
     * root too but fills the gene-product list only under the Level 3 namespace, so this guard is
     * defence-in-depth rather than a branch a fixture can currently distinguish
     * ({@code SbmlFbcGeneProductTest#readsNoGeneProductFromALevelTwoDocumentThatDeclaresFbc} pins the
     * reader's answer). The plugin is matched with {@code instanceof} rather than cast: a key that
     * answered another plugin type would otherwise abort the whole document with a
     * {@code ClassCastException}, where the intent is "nothing to iterate". A document with gene
     * products but no associations is an ordinary model.
     */
    private static void geneProducts(Model model, ArrayList<ParsedModelEntity> entities) {
        if (model.getLevel() != 3) {
            return;
        }
        if (!(model.getPlugin(FBCConstants.shortLabel) instanceof FBCModelPlugin plugin)
                || !plugin.isSetListOfGeneProducts()) {
            return;
        }
        for (var geneProduct : plugin.getListOfGeneProducts()) {
            var properties = new LinkedHashMap<String, String>();
            properties.put("id", nullToEmpty(geneProduct.getId()));
            if (geneProduct.isSetLabel() && !geneProduct.getLabel().isBlank()) {
                properties.put("label", geneProduct.getLabel());
            }
            if (geneProduct.getName() != null && !geneProduct.getName().isBlank()) {
                properties.put("name", geneProduct.getName());
            }
            entities.add(new ParsedModelEntity(
                    EntityType.SBML_GENE_PRODUCT,
                    nullToEmpty(geneProduct.getId()),
                    geneProductSearchName(geneProduct),
                    properties
            ));
        }
    }

    /**
     * The searchable name of a gene product: its {@code fbc:label} when the document states one, else
     * the element's own {@code name}. An entity's search aggregate is built from {@code id} and
     * {@code name} only, so the label has to sit in {@code name} to be findable; both attributes stay
     * on the properties as well, so the node keeps the document's own spelling either way. A gene
     * product's MIRIAM annotation is not read here: decision 52 scopes the {@code fbc} exception to identity.
     */
    private static String geneProductSearchName(GeneProduct geneProduct) {
        if (geneProduct.isSetLabel() && !geneProduct.getLabel().isBlank()) {
            return geneProduct.getLabel();
        }
        return nullToEmpty(geneProduct.getName());
    }

    /**
     * One {@code ASSOCIATED_WITH} edge per gene product a reaction's
     * {@code fbc:geneProductAssociation} references (decision 52).
     *
     * <p>The association is a boolean tree - {@code fbc:and}/{@code fbc:or} over
     * {@code fbc:geneProductRef} leaves - and the graph records the <em>reference</em>, not the
     * polarity: an edge says "this reaction references this gene product", never "positively
     * regulates", so a reader cannot mistake it for a flux direction. The walk therefore collects
     * every leaf of any nesting in document order and de-duplicates, because a reaction that names
     * one gene product twice is one reference, and the order is the one the document states.</p>
     *
     * <p>{@code jsbml-fbc} 1.6.1 declares no {@code Not} type, and the consequence is measured
     * (2026-11-24): a document carrying {@code <fbc:not>} still reads, the reader drops that subtree,
     * and {@code GeneProductAssociation.getAssociation()} answers {@code null}. Such a reaction
     * therefore gets no edge rather than a wrong one - a loss, not a lie - and {@code FBC-4} gives the
     * loss a voice: {@code sbml.droppedFbcNot} counts the dropped operands and the read is reduced.</p>
     */
    private static ArrayList<ParsedEntityRelationship> geneProductAssociations(Model model) {
        var relationships = new ArrayList<ParsedEntityRelationship>();
        if (model.getLevel() != 3) {
            return relationships;
        }
        model.getListOfReactions().forEach(reaction -> {
            if (!(reaction.getPlugin(FBCConstants.shortLabel) instanceof FBCReactionPlugin plugin)
                    || !plugin.isSetGeneProductAssociation()) {
                return;
            }
            var references = new LinkedHashSet<String>();
            collectGeneProductReferences(plugin.getGeneProductAssociation().getAssociation(), references);
            references.forEach(geneProduct -> relationships.add(new ParsedEntityRelationship(
                    EntityType.REACTION + ":" + reaction.getId(),
                    "ASSOCIATED_WITH",
                    EntityType.SBML_GENE_PRODUCT + ":" + geneProduct,
                    Map.of()
            )));
        });
        return relationships;
    }

    private static void collectGeneProductReferences(Association association, Set<String> references) {
        if (association instanceof GeneProductRef reference) {
            if (reference.isSetGeneProduct() && !reference.getGeneProduct().isBlank()) {
                references.add(reference.getGeneProduct());
            }
            return;
        }
        if (association instanceof LogicalOperator operator) {
            operator.getListOfAssociations()
                    .forEach(child -> collectGeneProductReferences(child, references));
        }
    }

    /**
     * The submodels and replaced elements of a composed document ({@code OPN-2}, SBML Level 3
     * {@code comp}). JSBML binds a Level 3 package plugin only in a Level 3 document, so the guard is
     * the Level 3 check plus the plugin's own list-of-submodels: a document that declares no
     * {@code comp} package yields no submodel and no edge, exactly as before this reader existed.
     *
     * <p>The plugin is matched with {@code instanceof} for the reason the {@code fbc} reader gives:
     * an unexpected plugin type means "nothing to iterate", never a {@code ClassCastException} that
     * aborts the whole document.</p>
     *
     * <p>No cross-document resolution is attempted ({@code OPN-3}'s rule: the reference is always
     * written, an edge to the target only when the target is in the graph - here the target is out of
     * scope). A submodel therefore always becomes a node carrying its {@code modelRef}, and
     * {@code COMPOSED_FROM} always starts at the {@code model} key, which is the key
     * {@link ParsedModelGraphMapper} gives the top model node.</p>
     *
     * <p>A replaced element is a {@code <comp:replacedElement>} inside the submodel's own
     * {@code <listOfReplacedElements>}; it carries {@code comp:idRef} and optionally
     * {@code comp:portRef} and {@code comp:conversionFactor}. {@code comp} does not say which kind of
     * element an {@code idRef} names, so the kind is the one of the document's own element with that
     * id (compartment, species or parameter, the three a replaced element is written for here); the
     * replaced id is resolved against the composed document's own entities. A reference that names an
     * element this document does not declare keeps its {@code idRef} and {@code COMPOSED_FROM}'s
     * submodel, and gets no {@code REPLACES} edge - the same rule as the {@code modelRef}, which is
     * written and never resolved.</p>
     */
    private static final class Composition {
        private final Model model;
        private final List<ParsedModelEntity> entities = new ArrayList<>();
        private final List<ParsedEntityRelationship> relationships = new ArrayList<>();

        private Composition(Model model) {
            this.model = model;
            if (model.getLevel() != 3) {
                return;
            }
            if (!(model.getPlugin(CompConstants.shortLabel) instanceof CompModelPlugin plugin)
                    || !plugin.isSetListOfSubmodels()) {
                return;
            }
            for (var submodel : plugin.getListOfSubmodels()) {
                var submodelId = nullToEmpty(submodel.getId());
                if (submodelId.isBlank()) {
                    // A submodel without an id cannot be keyed or referenced; there is nothing to write.
                    continue;
                }
                addSubmodel(submodel, submodelId);
            }
        }

        List<ParsedModelEntity> entities() {
            return entities;
        }

        List<ParsedEntityRelationship> relationships() {
            return relationships;
        }

        private void addSubmodel(Submodel submodel, String submodelId) {
            var modelRef = nullToEmpty(submodel.getModelRef());
            var properties = new LinkedHashMap<String, String>();
            properties.put("id", submodelId);
            properties.put("name", nullToEmpty(submodel.getName()));
            properties.put("modelRef", modelRef);
            if (submodel.isSetTimeConversionFactor()) {
                properties.put("timeConversionFactor", submodel.getTimeConversionFactor());
            }
            if (submodel.isSetExtentConversionFactor()) {
                properties.put("extentConversionFactor", submodel.getExtentConversionFactor());
            }
            entities.add(new ParsedModelEntity(
                    EntityType.SBML_SUBMODEL,
                    submodelId,
                    // The searchable name of a submodel is the document it refers to: that is the
                    // string a user knows the referenced model by, and a search aggregate is built
                    // from id and name only.
                    modelRef,
                    properties
            ));
            var submodelKey = EntityType.SBML_SUBMODEL + ":" + submodelId;
            // No COMPOSED_FROM here: ParsedModelGraphMapper derives the model's own edge from the entity
            // type (the `hasRelationshipFor` switch), exactly as it does for a species or a parameter. A
            // second, explicit edge would write the same relationship twice. The modelRef travels on the
            // node, where a search finds it.
            // Measured 2026-11-27 on jsbml-comp 1.6.1: a `<comp:replacedElement>` inside a submodel is
            // read into the submodel's own CompSBasePlugin, so that is where the list is read.
            if (submodel.getPlugin(CompConstants.shortLabel) instanceof CompSBasePlugin own
                    && own.isSetListOfReplacedElements()) {
                for (var replaced : own.getListOfReplacedElements()) {
                    addReplacedElement(submodelKey, replaced);
                }
            }
        }

        /**
         * One {@code REPLACES} edge per replaced element the submodel's {@code comp} plugin holds. The
         * element states the id it replaces; {@code comp} says no more about where that id lives, so
         * it is looked up among the composed document's own elements, the only ones in this graph. A
         * reference that names no such element keeps its {@code idRef} on the submodel node's
         * {@code COMPOSED_FROM} edge and gets no edge here - the reference is never resolved across
         * documents ({@code OPN-3}).
         */
        private void addReplacedElement(String submodelKey, ReplacedElement replaced) {
            var idRef = nullToEmpty(replaced.getIdRef());
            var target = replacedKey(idRef);
            if (target == null) {
                return;
            }
            var properties = new LinkedHashMap<String, String>();
            properties.put("idRef", idRef);
            if (replaced.isSetPortRef() && !replaced.getPortRef().isBlank()) {
                properties.put("portRef", replaced.getPortRef());
            }
            if (replaced.isSetConversionFactor() && !replaced.getConversionFactor().isBlank()) {
                properties.put("conversionFactor", replaced.getConversionFactor());
            }
            relationships.add(new ParsedEntityRelationship(submodelKey, "REPLACES", target, properties));
        }

        private String replacedKey(String idRef) {
            if (idRef.isBlank()) {
                return null;
            }
            if (model.getListOfCompartments().get(idRef) != null) {
                return EntityType.COMPARTMENT + ":" + idRef;
            }
            if (model.getListOfSpecies().get(idRef) != null) {
                return EntityType.SPECIES + ":" + idRef;
            }
            if (model.getListOfParameters().get(idRef) != null) {
                return EntityType.PARAMETER + ":" + idRef;
            }
            return null;
        }
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

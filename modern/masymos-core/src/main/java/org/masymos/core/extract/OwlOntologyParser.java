package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.masymos.core.model.ModelType;
import org.w3c.dom.Element;

public final class OwlOntologyParser {
    /**
     * A document that is none of the four OWL 2 serialisations; named so the error is not a guess.
     * The three non-RDF/XML serialisations are accepted since OWL-2 — see
     * {@link OwlSerialisationConverter}.
     */
    static final String UNRECOGNISED_SERIALISATION =
            "unrecognised OWL serialisation: expected RDF/XML (an rdf:RDF root element)";

    public ParsedOntology parse(ModelSource source, InputStream stream) {
        if (source.reference().modelType() != ModelType.OWL) {
            throw new IllegalArgumentException("OWL parser requires an OWL model source");
        }

        var content = OwlSerialisation.stripUtf8Bom(readFully(source, stream));
        var serialisation = OwlSerialisation.detect(content);

        if (serialisation == OwlSerialisation.UNRECOGNISED) {
            throw new ModelParseException(
                    UNRECOGNISED_SERIALISATION + ": " + source.reference().sourceUri(),
                    new UnsupportedOperationException(UNRECOGNISED_SERIALISATION)
            );
        }
        if (serialisation != OwlSerialisation.RDF_XML) {
            // OWL-2: the three non-RDF/XML serialisations are converted to RDF/XML and then read by
            // the same DOM code below, so there is exactly one mapping to ParsedOntology.
            content = new OwlSerialisationConverter().toRdfXml(content, serialisation, source.reference());
        }

        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
            var root = document.getDocumentElement();
            if (root == null) {
                throw new UnsupportedOperationException(
                        UNRECOGNISED_SERIALISATION
                                + ": the RDF/XML document has no root element in "
                                + source.reference().sourceUri()
                );
            }

            var ontologyName = ontologyName(root, source.reference().fileId());
            var ontologyLabel = sanitizeLabel(ontologyName);
            var termsById = new LinkedHashMap<String, ParsedOntologyTerm>();
            var relationships = new ArrayList<ParsedOntologyRelationship>();
            var rootId = "owl:" + ontologyLabel;

            termsById.put(rootId, new ParsedOntologyTerm(rootId, Map.of(
                    "id", rootId,
                    "name", ontologyLabel,
                    "ontology", ontologyLabel,
                    "termType", "root"
            )));

            var objectProperties = propertyIds(root, "ObjectProperty");
            var dataProperties = propertyIds(root, "DatatypeProperty");
            propertyTerms(root, ontologyLabel).forEach(term -> termsById.putIfAbsent(term.id(), term));

            for (var ontologyClass : directChildren(root, "Class")) {
                var classId = compactIdentifier(resource(ontologyClass));
                if (classId.isBlank()) {
                    continue;
                }
                termsById.putIfAbsent(classId, new ParsedOntologyTerm(classId, termProperties(classId, ontologyClass, ontologyLabel)));

                var statedParents = parents(ontologyClass);
                var definedParents = definedParents(ontologyClass, statedParents);
                if (statedParents.isEmpty() && definedParents.isEmpty()) {
                    relationships.add(new ParsedOntologyRelationship(classId, "isA", rootId, Map.of()));
                } else {
                    addParentRelationships(relationships, termsById, ontologyLabel, classId, statedParents, Map.of());
                    addParentRelationships(relationships, termsById, ontologyLabel, classId, definedParents,
                            Map.of("derivedFrom", "equivalentClass"));
                }
                for (var restriction : restrictions(ontologyClass)) {
                    termsById.putIfAbsent(restriction.endId(), new ParsedOntologyTerm(restriction.endId(), Map.of(
                            "id", restriction.endId(),
                            "name", restriction.endId(),
                            "ontology", ontologyLabel,
                            "termType", "class"
                    )));
                    relationships.add(new ParsedOntologyRelationship(
                            classId,
                            restriction.type(),
                            restriction.endId(),
                            Map.of("onProperty", restriction.onProperty())
                    ));
                }
            }

            for (var individual : directChildren(root, "NamedIndividual")) {
                importIndividual(individual, ontologyLabel, objectProperties, dataProperties, termsById, relationships);
            }
            for (var description : directChildren(root, "Description")) {
                importIndividual(description, ontologyLabel, objectProperties, dataProperties, termsById, relationships);
            }

            return new ParsedOntology(
                    source.reference(),
                    ontologyName,
                    ontologyLabel,
                    ontologyProperties(root, ontologyLabel, termsById.size(), relationships.size()),
                    List.copyOf(termsById.values()),
                    List.copyOf(relationships)
            );
        } catch (Exception exception) {
            throw new ModelParseException(
                    "failed to parse OWL ontology: " + source.reference().sourceUri(),
                    exception
            );
        }
    }

    private static byte[] readFully(ModelSource source, InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new ModelParseException(
                    "failed to read OWL ontology: " + source.reference().sourceUri(),
                    exception
            );
        }
    }

    private static Map<String, String> ontologyProperties(Element root, String ontologyLabel, int termCount, int relationshipCount) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("ontology.name", ontologyLabel);
        properties.put("ontology.terms", Integer.toString(termCount));
        properties.put("ontology.relationships", Integer.toString(relationshipCount));
        properties.put("ontology.base", nullToEmpty(root.getAttribute("xml:base")));
        properties.put("ontology.objectProperties", Integer.toString(directChildren(root, "ObjectProperty").size()));
        properties.put("ontology.dataProperties", Integer.toString(directChildren(root, "DatatypeProperty").size()));
        properties.put("ontology.annotationProperties", Integer.toString(directChildren(root, "AnnotationProperty").size()));
        var ontology = directChildren(root, "Ontology").stream().findFirst().orElse(null);
        if (ontology != null) {
            properties.put("ontology.comment", textOfFirstChild(ontology, "comment"));
            properties.put("ontology.version", textOfFirstChild(ontology, "versionInfo"));
            properties.put("ontology.label", textOfFirstChild(ontology, "label"));
        }
        return Map.copyOf(properties);
    }

    private static List<ParsedOntologyTerm> propertyTerms(Element root, String ontologyLabel) {
        var terms = new ArrayList<ParsedOntologyTerm>();
        for (var property : directChildren(root, "ObjectProperty")) {
            var id = compactIdentifier(resource(property));
            if (!id.isBlank()) {
                terms.add(new ParsedOntologyTerm(id, propertyProperties(id, property, ontologyLabel, "objectProperty")));
            }
        }
        for (var property : directChildren(root, "AnnotationProperty")) {
            var id = compactIdentifier(resource(property));
            if (!id.isBlank()) {
                terms.add(new ParsedOntologyTerm(id, propertyProperties(id, property, ontologyLabel, "annotationProperty")));
            }
        }
        for (var property : directChildren(root, "DatatypeProperty")) {
            var id = compactIdentifier(resource(property));
            if (!id.isBlank()) {
                terms.add(new ParsedOntologyTerm(id, dataPropertyProperties(id, property, ontologyLabel)));
            }
        }
        return List.copyOf(terms);
    }

    private static Map<String, String> dataPropertyProperties(String id, Element property, String ontologyLabel) {
        var properties = new LinkedHashMap<>(propertyProperties(id, property, ontologyLabel, "dataProperty"));
        var range = dataPropertyRange(property);
        if (range.isBlank()) {
            properties.remove("range");
        } else {
            properties.put("range", range);
        }
        return Map.copyOf(properties);
    }

    private static String dataPropertyRange(Element property) {
        var rangeElement = firstChild(property, "range");
        if (rangeElement == null) {
            return "";
        }
        var directResource = compactIdentifier(resource(rangeElement));
        if (!directResource.isBlank()) {
            return directResource;
        }
        return directChildren(rangeElement, null).stream()
                .map(child -> compactIdentifier(resource(child)))
                .filter(id -> !id.isBlank())
                .findFirst()
                .orElse("");
    }

    private static List<String> propertyIds(Element root, String localName) {
        return directChildren(root, localName).stream()
                .map(OwlOntologyParser::resource)
                .map(OwlOntologyParser::compactIdentifier)
                .filter(id -> !id.isBlank())
                .toList();
    }

    private static void importIndividual(
            Element individual,
            String ontologyLabel,
            List<String> objectProperties,
            List<String> dataProperties,
            Map<String, ParsedOntologyTerm> termsById,
            List<ParsedOntologyRelationship> relationships
    ) {
        var individualId = compactIdentifier(resource(individual));
        if (individualId.isBlank()) {
            return;
        }

        var properties = new LinkedHashMap<String, String>();
        properties.put("id", individualId);
        properties.put("name", individualId);
        properties.put("ontology", ontologyLabel);
        properties.put("termType", "individual");

        for (var child : directChildren(individual, null)) {
            var childName = child.getLocalName();
            if ("type".equals(childName)) {
                var classId = compactIdentifier(resource(child));
                if (!classId.isBlank() && !isOwlNamedIndividual(classId)) {
                    termsById.putIfAbsent(classId, new ParsedOntologyTerm(classId, Map.of(
                            "id", classId,
                            "name", classId,
                            "ontology", ontologyLabel,
                            "termType", "class"
                    )));
                    relationships.add(new ParsedOntologyRelationship(individualId, "isA", classId, Map.of()));
                }
                continue;
            }
            if (isAnnotationElement(childName)) {
                continue;
            }

            var propertyId = compactIdentifier(firstPresent(resource(child), childName));
            var assertionProperty = sanitizeRelationshipType(childName);
            var objectId = compactIdentifier(resource(child));
            if (!objectId.isBlank() && (objectProperties.contains(assertionProperty) || !dataProperties.contains(assertionProperty))) {
                termsById.putIfAbsent(objectId, new ParsedOntologyTerm(objectId, Map.of(
                        "id", objectId,
                        "name", objectId,
                        "ontology", ontologyLabel,
                        "termType", "individual"
                )));
                relationships.add(new ParsedOntologyRelationship(individualId, assertionProperty, objectId, Map.of()));
            } else if (!propertyId.isBlank()) {
                var value = child.getTextContent() == null ? "" : child.getTextContent().trim();
                if (!value.isBlank()) {
                    properties.put(sanitizePropertyName(assertionProperty), value);
                }
            }
        }

        termsById.put(individualId, new ParsedOntologyTerm(individualId, Map.copyOf(properties)));
    }

    private static boolean isOwlNamedIndividual(String classId) {
        return "NamedIndividual".equals(classId) || classId.endsWith("#NamedIndividual");
    }

    private static boolean isAnnotationElement(String localName) {
        return "label".equals(localName)
                || "comment".equals(localName)
                || "versionInfo".equals(localName)
                || "sameAs".equals(localName);
    }

    private static String sanitizeRelationshipType(String value) {
        var sanitized = sanitizePropertyName(value);
        return sanitized.isBlank() ? "relatedTo" : sanitized;
    }

    private static String sanitizePropertyName(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static Map<String, String> propertyProperties(String id, Element property, String ontologyLabel, String termType) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("id", id);
        properties.put("name", firstPresent(textOfFirstChild(property, "label"), id));
        properties.put("comment", textOfFirstChild(property, "comment"));
        properties.put("definition", firstPresent(textOfFirstChild(property, "definition"), textOfFirstChild(property, "OwlDef")));
        properties.put("ontology", ontologyLabel);
        properties.put("termType", termType);
        properties.put("domain", compactIdentifier(resource(firstChild(property, "domain"))));
        properties.put("range", compactIdentifier(resource(firstChild(property, "range"))));
        return Map.copyOf(properties);
    }

    private static Map<String, String> termProperties(String classId, Element ontologyClass, String ontologyLabel) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("id", classId);
        properties.put("name", firstPresent(textOfFirstChild(ontologyClass, "label"), classId));
        properties.put("comment", textOfFirstChild(ontologyClass, "comment"));
        properties.put("definition", textOfFirstChild(ontologyClass, "definition"));
        properties.put("ontology", ontologyLabel);
        properties.put("termType", "class");
        return Map.copyOf(properties);
    }

    private static List<String> parents(Element ontologyClass) {
        var parents = new ArrayList<String>();
        for (var subclass : directChildren(ontologyClass, "subClassOf")) {
            var directResource = compactIdentifier(resource(subclass));
            if (!directResource.isBlank()) {
                parents.add(directResource);
                continue;
            }
            directChildren(subclass, "Class").stream()
                    .map(OwlOntologyParser::resource)
                    .map(OwlOntologyParser::compactIdentifier)
                    .filter(parent -> !parent.isBlank())
                    .forEach(parents::add);
        }
        return List.copyOf(parents);
    }

    /**
     * The named conjunct of a genus-differentia definition: {@code A equivalentClass (G and exists r.C)}
     * states {@code A rdfs:subClassOf G}, and OWLAPI's structural reasoner reported {@code G} as a direct
     * superclass (measured 2026-11-17, decision 38). Only a named member of an {@code intersectionOf} is
     * taken, because that is all the reasoner returned: a restriction member carries no IRI, a union
     * implies nothing about a superclass, and {@code A equivalentClass B} between two named classes folds
     * them into one node without an edge on either side. A genus already stated as a
     * {@code rdfs:subClassOf} is not repeated, so no document gains a second edge for the same pair.
     */
    private static List<String> definedParents(Element ontologyClass, List<String> statedParents) {
        var definedParents = new ArrayList<String>();
        for (var equivalence : directChildren(ontologyClass, "equivalentClass")) {
            var conjunction = firstChild(firstChild(equivalence, "Class"), "intersectionOf");
            if (conjunction == null) {
                continue;
            }
            for (var member : directChildren(conjunction, null)) {
                var memberId = compactIdentifier(resource(member));
                if (memberId.isBlank() || statedParents.contains(memberId) || definedParents.contains(memberId)) {
                    continue;
                }
                definedParents.add(memberId);
            }
        }
        return List.copyOf(definedParents);
    }

    private static void addParentRelationships(
            List<ParsedOntologyRelationship> relationships,
            Map<String, ParsedOntologyTerm> termsById,
            String ontologyLabel,
            String classId,
            List<String> parentIds,
            Map<String, String> properties
    ) {
        for (var parentId : parentIds) {
            termsById.putIfAbsent(parentId, new ParsedOntologyTerm(parentId, Map.of(
                    "id", parentId,
                    "name", parentId,
                    "ontology", ontologyLabel,
                    "termType", "class"
            )));
            relationships.add(new ParsedOntologyRelationship(classId, "isA", parentId, properties));
        }
    }

    private static List<RestrictionInfo> restrictions(Element ontologyClass) {
        var restrictions = new ArrayList<RestrictionInfo>();
        collectRestrictions(ontologyClass, restrictions);
        return List.copyOf(restrictions);
    }

    private static void collectRestrictions(Element element, List<RestrictionInfo> restrictions) {
        if ("Restriction".equals(element.getLocalName())) {
            var onProperty = compactIdentifier(resource(firstChild(element, "onProperty")));
            if (!onProperty.isBlank()) {
                addRestriction(element, onProperty, "someValuesFrom", "someValuesFrom", restrictions);
                addRestriction(element, onProperty, "allValuesFrom", "allValuesFrom", restrictions);
                addCombinatorRestrictions(element, onProperty, restrictions);
            }
        }
        var nodes = element.getChildNodes();
        for (var index = 0; index < nodes.getLength(); index++) {
            var node = nodes.item(index);
            if (node instanceof Element child) {
                collectRestrictions(child, restrictions);
            }
        }
    }

    private static void addRestriction(
            Element restriction,
            String onProperty,
            String localName,
            String relationshipType,
            List<RestrictionInfo> restrictions
    ) {
        for (var classId : restrictionClassIds(firstChild(restriction, localName))) {
            restrictions.add(new RestrictionInfo(onProperty, relationshipType, classId));
        }
    }

    private static void addCombinatorRestrictions(
            Element restriction,
            String onProperty,
            List<RestrictionInfo> restrictions
    ) {
        for (var combinator : List.of("unionOf", "intersectionOf", "complementOf")) {
            for (var node : descendants(restriction, combinator)) {
                for (var classId : restrictionClassIds(node)) {
                    restrictions.add(new RestrictionInfo(onProperty, combinator, classId));
                }
            }
        }
    }

    /**
     * Anonymous class combinators are nested below the filler element
     * ({@code someValuesFrom/Class/unionOf}), so they cannot be looked up as direct children.
     */
    private static List<Element> descendants(Element element, String localName) {
        var found = new ArrayList<Element>();
        var nodes = element.getChildNodes();
        for (var index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element child) {
                if (localName.equals(child.getLocalName())) {
                    found.add(child);
                }
                found.addAll(descendants(child, localName));
            }
        }
        return found;
    }

    private static List<String> restrictionClassIds(Element filler) {
        if (filler == null) {
            return List.of();
        }
        var classIds = new ArrayList<String>();
        var directResource = compactIdentifier(resource(filler));
        if (!directResource.isBlank()) {
            classIds.add(directResource);
            return List.copyOf(classIds);
        }
        for (var node : directChildren(filler, null)) {
            var nodeResource = compactIdentifier(resource(node));
            if (!nodeResource.isBlank()) {
                classIds.add(nodeResource);
                continue;
            }
            var nodeId = compactIdentifier(node.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "ID"));
            if (!nodeId.isBlank()) {
                classIds.add(nodeId);
            }
        }
        return List.copyOf(classIds);
    }

    private static String resource(Element element) {
        if (element == null) {
            return "";
        }
        var about = element.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "about");
        if (!about.isBlank()) {
            return about;
        }
        return element.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "resource");
    }

    private static String compactIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var normalized = value.trim();
        var hashIndex = normalized.lastIndexOf('#');
        if (hashIndex >= 0 && hashIndex < normalized.length() - 1) {
            return normalized.substring(hashIndex + 1);
        }
        var slashIndex = normalized.lastIndexOf('/');
        if (slashIndex >= 0 && slashIndex < normalized.length() - 1) {
            return normalized.substring(slashIndex + 1);
        }
        return normalized;
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

    private static Element firstChild(Element element, String localName) {
        return directChildren(element, localName).stream().findFirst().orElse(null);
    }

    private static String textOfFirstChild(Element element, String localName) {
        return directChildren(element, localName).stream()
                .findFirst()
                .map(Element::getTextContent)
                .map(String::trim)
                .orElse("");
    }

    /**
     * Ontology identity, preferred from the document over the file name: the OWL-3 migration restores the
     * legacy names (SBO, KISAO, GO, ChEBI) by what the ontology <em>is</em>. The {@code <owl:Ontology>}
     * IRI is read first; the file id is only a fallback for a document that does not state one.
     */
    private static String ontologyName(Element root, String fileId) {
        var declared = declaredOntologyName(ontologyIri(root));
        if (declared != null) {
            return declared;
        }
        return ontologyName(fileId);
    }

    private static String ontologyIri(Element root) {
        var ontology = firstChild(root, "Ontology");
        if (ontology == null) {
            return "";
        }
        var about = resource(ontology);
        if (!about.isBlank()) {
            return about.trim();
        }
        return ontology.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "ID").trim();
    }

    /**
     * Matches the terminal token of the ontology IRI, never the whole IRI: {@code dgo.owl} must not be
     * mistaken for GO. Compound segments such as {@code KISAO_FULL} are also matched by their leading
     * {@code _}-separated part. Returns {@code null} when the IRI names no known ontology.
     */
    private static String declaredOntologyName(String iri) {
        var token = iriTerminalToken(iri);
        var head = token.contains("_") ? token.substring(0, token.indexOf('_')) : token;
        if (!head.equals(token) && !head.isEmpty()) {
            var byHead = declaredOntologyNameFor(head);
            if (byHead != null) {
                return byHead;
            }
        }
        return declaredOntologyNameFor(token);
    }

    private static String declaredOntologyNameFor(String token) {
        return switch (token) {
            case "sbo" -> "SBOOntology";
            case "kisao" -> "KISAOOntology";
            case "go", "go-basic", "geneontology" -> "GOOntology";
            case "chebi" -> "ChebiOntology";
            default -> null;
        };
    }

    /**
     * Last path segment (or fragment) of the IRI, lowercased, extension stripped. An IRI that ends in a
     * separator, such as {@code http://www.biomodels.net/kisao/KISAO_FULL#}, contributes its preceding
     * segment instead.
     */
    private static String iriTerminalToken(String iri) {
        if (iri == null || iri.isBlank()) {
            return "";
        }
        var trimmed = iri.trim();
        var end = trimmed.length();
        while (end > 0 && isIriSeparator(trimmed.charAt(end - 1))) {
            end--;
        }
        var start = end;
        while (start > 0 && !isIriSeparator(trimmed.charAt(start - 1))) {
            start--;
        }
        var segment = trimmed.substring(start, end).toLowerCase(Locale.ROOT);
        for (var extension : List.of(".obo", ".owl")) {
            if (segment.endsWith(extension) && segment.length() > extension.length()) {
                segment = segment.substring(0, segment.length() - extension.length());
            }
        }
        return segment;
    }

    private static boolean isIriSeparator(char value) {
        return value == '/' || value == '#' || value == ':';
    }

    /** File-name fallback, kept for documents that do not state an ontology IRI. */
    private static String ontologyName(String fileId) {
        var lower = fileId.toLowerCase(Locale.ROOT);
        if (lower.contains("sbo")) {
            return "SBOOntology";
        }
        if (lower.contains("kisao")) {
            return "KISAOOntology";
        }
        var baseName = fileId.replaceFirst("\\.[^.]+$", "");
        return sanitizeLabel(baseName + "Ontology");
    }

    private static String sanitizeLabel(String value) {
        var sanitized = value.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isBlank()) {
            return "OWLOntology";
        }
        return Character.isJavaIdentifierStart(sanitized.charAt(0)) ? sanitized : "Ontology_" + sanitized;
    }

    private static String firstPresent(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? nullToEmpty(fallback) : preferred;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record RestrictionInfo(String onProperty, String type, String endId) {
    }
}

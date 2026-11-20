package org.masymos.core.extract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.masymos.core.annotation.AnnotationPrefixCatalogue;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.extract.ParsedReactionParticipant.Role;
import org.masymos.core.query.IndexText;

public final class ParsedModelGraphMapper {
    public ExtractedModelGraph toGraph(ParsedModel parsedModel) {
        var nodes = new ArrayList<ExtractedGraphNode>();
        var relationships = new ArrayList<ExtractedGraphRelationship>();

        var documentProperties = new LinkedHashMap<String, String>();
        documentProperties.put("fileId", parsedModel.reference().fileId());
        documentProperties.put("sourceUri", parsedModel.reference().sourceUri().toString());
        documentProperties.put("modelType", parsedModel.reference().modelType().name());
        documentProperties.putAll(parsedModel.properties());
        putVersionId(documentProperties, parsedModel.reference().versionId());

        var modelProperties = new LinkedHashMap<String, String>();
        modelProperties.put("fileId", parsedModel.reference().fileId());
        modelProperties.put("sourceUri", parsedModel.reference().sourceUri().toString());
        modelProperties.put("modelId", nullToEmpty(parsedModel.modelId()));
        modelProperties.put("name", nullToEmpty(parsedModel.modelName()));
        putVersionId(modelProperties, parsedModel.reference().versionId());
        modelProperties.putAll(schemaVersionProperties(parsedModel.properties()));
        modelProperties.putAll(searchProperties(parsedModel));

        var document = new ExtractedGraphNode(
                "document",
                Set.of("Document"),
                Map.copyOf(documentProperties)
        );
        nodes.add(document);

        var model = new ExtractedGraphNode(
                "model",
                Set.of("Model", parsedModel.reference().modelType().name() + "_MODEL"),
                Map.copyOf(modelProperties)
        );
        nodes.add(model);
        relationships.add(new ExtractedGraphRelationship(
                "document",
                "HAS_MODEL",
                "model",
                Map.of()
        ));

        var entityKeys = new HashMap<String, String>();
        var usedUnits = new HashMap<String, String>();
        parsedModel.entities().forEach(entity -> {
            var entityKey = entityKey(entity);
            entityKeys.put(entity.type() + ":" + entity.id(), entityKey);
            nodes.add(new ExtractedGraphNode(entityKey, labels(entity.type()), entity.properties()));
            if (entity.type() == EntityType.UNIT) {
                // A unit is a shared node: the model points at it, it never belongs to the model.
                if (usedUnits.putIfAbsent(entityKey, entityKey) == null) {
                    relationships.add(new ExtractedGraphRelationship("model", "USES_UNIT", entityKey, Map.of()));
                }
                return;
            }
            relationships.add(new ExtractedGraphRelationship("model", hasRelationship(entity.type()), entityKey, Map.of()));
            relationships.add(new ExtractedGraphRelationship(entityKey, "BELONGS_TO", "model", Map.of()));
        });

        parsedModel.entities().stream()
                .filter(entity -> entity.type() == EntityType.SPECIES)
                .forEach(species -> {
                    var compartmentId = species.properties().get("compartment");
                    var compartmentKey = entityKeys.get(EntityType.COMPARTMENT + ":" + compartmentId);
                    if (compartmentKey != null) {
                        var speciesKey = entityKeys.get(EntityType.SPECIES + ":" + species.id());
                        relationships.add(new ExtractedGraphRelationship(speciesKey, "IS_LOCATED_IN", compartmentKey, Map.of()));
                        relationships.add(new ExtractedGraphRelationship(compartmentKey, "CONTAINS_SPECIES", speciesKey, Map.of()));
                    }
                });

        parsedModel.entities().stream()
                .filter(entity -> entity.type() == EntityType.REACTION)
                .forEach(reaction -> {
                    var compartmentId = reaction.properties().get("compartment");
                    var compartmentKey = entityKeys.get(EntityType.COMPARTMENT + ":" + compartmentId);
                    if (compartmentKey != null) {
                        var reactionKey = entityKeys.get(EntityType.REACTION + ":" + reaction.id());
                        relationships.add(new ExtractedGraphRelationship(reactionKey, "IS_LOCATED_IN", compartmentKey, Map.of()));
                        relationships.add(new ExtractedGraphRelationship(compartmentKey, "CONTAINS_REACTION", reactionKey, Map.of()));
                    }
                });

        parsedModel.reactionParticipants().forEach(participant -> {
            var reactionKey = entityKeys.get(EntityType.REACTION + ":" + participant.reactionId());
            var speciesKey = entityKeys.get(EntityType.SPECIES + ":" + participant.speciesId());
            if (reactionKey != null && speciesKey != null) {
                relationships.add(new ExtractedGraphRelationship(
                        reactionKey,
                        hasParticipantRelationship(participant.role()),
                        speciesKey,
                        participant.properties()
                ));
                relationships.add(new ExtractedGraphRelationship(
                        speciesKey,
                        isParticipantRelationship(participant.role()),
                        reactionKey,
                        participant.properties()
                ));
            }
        });

        parsedModel.entityRelationships().forEach(entityRelationship -> {
            var startKey = entityKeys.get(entityRelationship.startEntityKey());
            var endKey = entityKeys.get(entityRelationship.endEntityKey());
            if (startKey != null && endKey != null) {
                relationships.add(new ExtractedGraphRelationship(
                        startKey,
                        entityRelationship.type(),
                        endKey,
                        entityRelationship.properties()
                ));
            }
        });

        for (var annotationIndex = 0; annotationIndex < parsedModel.annotations().size(); annotationIndex++) {
            var annotation = parsedModel.annotations().get(annotationIndex);
            if (!nodeKeyExists(nodes, annotation.subjectKey())) {
                continue;
            }
            var annotationKey = "annotation:" + annotationIndex + ":" + annotation.subjectKey();
            nodes.add(new ExtractedGraphNode(annotationKey, Set.of("ANNOTATION"), annotation.properties()));
            relationships.add(new ExtractedGraphRelationship(annotation.subjectKey(), "HAS_ANNOTATION", annotationKey, Map.of()));
            relationships.add(new ExtractedGraphRelationship(annotationKey, "BELONGS_TO", annotation.subjectKey(), Map.of()));

            annotation.resources().forEach(resource -> {
                var resourceKey = "resource:" + resource.uri();
                if (!nodeKeyExists(nodes, resourceKey)) {
                    nodes.add(new ExtractedGraphNode(resourceKey, Set.of("RESOURCE"), Map.of("uri", resource.uri())));
                }
                relationships.add(new ExtractedGraphRelationship(annotationKey, relationshipType(resource.qualifier()), resourceKey, Map.of()));
                relationships.add(new ExtractedGraphRelationship(resourceKey, "BELONGS_TO", annotationKey, Map.of()));
            });

            annotation.sboTerms().forEach(uri -> {
                var resourceKey = "resource:" + uri;
                if (!nodeKeyExists(nodes, resourceKey)) {
                    nodes.add(new ExtractedGraphNode(resourceKey, Set.of("RESOURCE"), Map.of("uri", uri)));
                }
                relationships.add(new ExtractedGraphRelationship(annotationKey, "HAS_SBOTERM", resourceKey, Map.of()));
                relationships.add(new ExtractedGraphRelationship(resourceKey, "BELONGS_TO", annotationKey, Map.of()));
            });

            annotation.creators().forEach(creator -> {
                var personKey = "person:" + creator.givenName() + ":" + creator.familyName() + ":" + creator.email();
                if (!nodeKeyExists(nodes, personKey)) {
                    nodes.add(new ExtractedGraphNode(personKey, Set.of("PERSON"), creator.properties()));
                }
                relationships.add(new ExtractedGraphRelationship(annotationKey, "IS_CREATOR", personKey, Map.of()));
                relationships.add(new ExtractedGraphRelationship(personKey, "BELONGS_TO", annotationKey, Map.of()));
            });
        }

        // ANNO-1a: SED-ML carries its algorithm term as an attribute rather than as an RDF resource,
        // so without this the KISAO term is invisible to a URI pass. The URI is derived from the
        // catalogue, never from the raw attribute: both spellings the corpus shows
        // (KISAO:0000019, KISAO_0000019) collapse to one node.
        parsedModel.entities().stream()
                .filter(entity -> entity.type() == EntityType.SEDML_SIMULATION)
                .forEach(simulation -> {
                    var kisaoUri = kisaoUri(simulation.properties().get("simKisao"));
                    if (kisaoUri.isEmpty()) {
                        return;
                    }
                    var uri = kisaoUri.get();
                    var resourceKey = "resource:" + uri;
                    if (!nodeKeyExists(nodes, resourceKey)) {
                        nodes.add(new ExtractedGraphNode(resourceKey, Set.of("RESOURCE"), Map.of("uri", uri)));
                    }
                    var simulationKey = entityKeys.get(EntityType.SEDML_SIMULATION + ":" + simulation.id());
                    if (simulationKey == null) {
                        return;
                    }
                    relationships.add(new ExtractedGraphRelationship(simulationKey, "HAS_KISAO", resourceKey, Map.of()));
                    relationships.add(new ExtractedGraphRelationship(resourceKey, "BELONGS_TO", simulationKey, Map.of()));
                });

        for (var publicationIndex = 0; publicationIndex < parsedModel.publications().size(); publicationIndex++) {
            var publication = parsedModel.publications().get(publicationIndex);
            var publicationKey = publicationKey(publication, publicationIndex);
            var publicationProperties = new LinkedHashMap<>(publication.properties());
            putSearchProperty(publicationProperties, "author", publication.authors().stream()
                    .flatMap(author -> List.of(author.givenName(), author.familyName()).stream())
                    .toList());
            nodes.add(new ExtractedGraphNode(publicationKey, Set.of("PUBLICATION"), Map.copyOf(publicationProperties)));
            relationships.add(new ExtractedGraphRelationship("model", "HAS_PUBLICATION", publicationKey, Map.of()));
            relationships.add(new ExtractedGraphRelationship(publicationKey, "BELONGS_TO", "model", Map.of()));

            publication.authors().forEach(author -> {
                var personKey = personKey(author);
                if (!nodeKeyExists(nodes, personKey)) {
                    nodes.add(new ExtractedGraphNode(personKey, Set.of("PERSON"), author.properties()));
                }
                relationships.add(new ExtractedGraphRelationship(publicationKey, "HAS_AUTHOR", personKey, Map.of()));
                relationships.add(new ExtractedGraphRelationship(personKey, "BELONGS_TO", publicationKey, Map.of()));
            });
        }

        return new ExtractedModelGraph(parsedModel.reference(), List.copyOf(nodes), List.copyOf(relationships));
    }

    private boolean nodeKeyExists(ArrayList<ExtractedGraphNode> nodes, String nodeKey) {
        return nodes.stream().anyMatch(node -> node.key().equals(nodeKey));
    }

    /**
     * The schema level/version of the source document belongs on the model node as well, so a query
     * result can tell which SBML/CellML/SED-ML version a model was written in. Without this the
     * information exists only on the Document node.
     */
    private static Map<String, String> schemaVersionProperties(Map<String, String> parsedProperties) {
        var versionProperties = new LinkedHashMap<String, String>();
        copyVersionProperty(parsedProperties, "sbml.level", "sbmlLevel", versionProperties);
        copyVersionProperty(parsedProperties, "sbml.version", "sbmlVersion", versionProperties);
        copyVersionProperty(parsedProperties, "cellml.version", "cellmlVersion", versionProperties);
        copyVersionProperty(parsedProperties, "sedml.level", "sedmlLevel", versionProperties);
        copyVersionProperty(parsedProperties, "sedml.version", "sedmlVersion", versionProperties);
        return versionProperties;
    }

    private static void copyVersionProperty(
            Map<String, String> source,
            String sourceKey,
            String targetKey,
            Map<String, String> target
    ) {
        var value = source.get(sourceKey);
        if (value != null && !value.isBlank()) {
            target.put(targetKey, value);
        }
    }

    private Map<String, String> searchProperties(ParsedModel parsedModel) {
        var properties = new LinkedHashMap<String, String>();
        putEntitySearchProperty(properties, parsedModel, EntityType.COMPARTMENT, "compartment");
        putEntitySearchProperty(properties, parsedModel, EntityType.SPECIES, "species");
        putEntitySearchProperty(properties, parsedModel, EntityType.REACTION, "reaction");
        putEntitySearchProperty(properties, parsedModel, EntityType.PARAMETER, "parameter");
        putEntitySearchProperty(properties, parsedModel, EntityType.RULE, "rule");
        putEntitySearchProperty(properties, parsedModel, EntityType.FUNCTION, "function");
        putEntitySearchProperty(properties, parsedModel, EntityType.EVENT, "event");
        putEntitySearchProperty(properties, parsedModel, EntityType.COMPONENT, "component");
        putEntitySearchProperty(properties, parsedModel, EntityType.VARIABLE, "variable");
        putEntityPropertySearchProperty(properties, parsedModel, EntityType.SEDML_MODELREFERENCE, "modelSource", "modelSource");
        putEntityPropertySearchProperty(properties, parsedModel, EntityType.SEDML_OUTPUT, "outputType", "outputType");
        putEntityPropertySearchProperty(properties, parsedModel, EntityType.SEDML_DATASET, "dataLabel", "dataLabel");
        putEntityPropertySearchProperty(properties, parsedModel, EntityType.SEDML_SIMULATION, "simKisao", "simKisao");
        putEntityPropertySearchProperty(properties, parsedModel, EntityType.SEDML_SIMULATION, "simType", "simType");
        putEntityPropertySearchProperty(properties, parsedModel, EntityType.SEDML_DATAGENERATOR, "math", "math");
        putEntitySearchProperty(properties, parsedModel, EntityType.SEDML_VARIABLE, "variable");
        putEntitySearchProperty(properties, parsedModel, EntityType.SEDML_OUTPUT, "output");
        putCreatorSearchProperties(properties, parsedModel);
        putPublicationSearchProperties(properties, parsedModel);
        putAnnotationSearchProperties(properties, parsedModel);
        return properties;
    }

    private void putEntitySearchProperty(
            LinkedHashMap<String, String> properties,
            ParsedModel parsedModel,
            EntityType type,
            String propertyName
    ) {
        var value = parsedModel.entities().stream()
                .filter(entity -> entity.type() == type)
                .flatMap(entity -> List.of(entity.id(), entity.name()).stream())
                .filter(valuePart -> valuePart != null && !valuePart.isBlank())
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        if (!value.isBlank()) {
            properties.put(propertyName, searchValue(value));
        }
    }

    private void putCreatorSearchProperties(LinkedHashMap<String, String> properties, ParsedModel parsedModel) {
        var creators = parsedModel.annotations().stream()
                .flatMap(annotation -> annotation.creators().stream())
                .toList();
        var creatorNames = creators.stream()
                .flatMap(creator -> List.of(creator.givenName(), creator.familyName()).stream())
                .filter(valuePart -> valuePart != null && !valuePart.isBlank())
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        if (!creatorNames.isBlank()) {
            properties.put("creator", creatorNames);
        }
        var emails = creators.stream()
                .map(ParsedPerson::email)
                .filter(valuePart -> valuePart != null && !valuePart.isBlank())
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        if (!emails.isBlank()) {
            properties.put("email", emails);
        }
        var organizations = creators.stream()
                .map(ParsedPerson::organization)
                .filter(valuePart -> valuePart != null && !valuePart.isBlank())
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        if (!organizations.isBlank()) {
            properties.put("organization", organizations);
        }
    }

    private void putPublicationSearchProperties(LinkedHashMap<String, String> properties, ParsedModel parsedModel) {
        putSearchProperty(properties, "author", parsedModel.publications().stream()
                .flatMap(publication -> publication.authors().stream())
                .flatMap(author -> List.of(author.givenName(), author.familyName()).stream())
                .toList());
        putSearchProperty(properties, "title", parsedModel.publications().stream().map(ParsedPublication::title).toList());
        putSearchProperty(properties, "journal", parsedModel.publications().stream().map(ParsedPublication::journal).toList());
        putSearchProperty(properties, "abstractText", parsedModel.publications().stream().map(ParsedPublication::abstractText).toList());
        putSearchProperty(properties, "affiliation", parsedModel.publications().stream().map(ParsedPublication::affiliation).toList());
        putSearchProperty(properties, "year", parsedModel.publications().stream().map(ParsedPublication::year).toList());
    }

    private void putSearchProperty(LinkedHashMap<String, String> properties, String propertyName, List<String> values) {
        var value = values.stream()
                .filter(valuePart -> valuePart != null && !valuePart.isBlank())
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        if (!value.isBlank()) {
            properties.put(propertyName, value);
        }
    }

    /**
     * Every aggregate search value passes the legacy {@code IndexText} helpers, so separator-split
     * fragments are indexed and an oversized value cannot break the fulltext index.
     */
    private static String searchValue(String value) {
        return IndexText.expandTermsSpecialChars(IndexText.shrinkToMaxTermLength(value));
    }

    private void putEntityPropertySearchProperty(
            LinkedHashMap<String, String> properties,
            ParsedModel parsedModel,
            EntityType type,
            String entityPropertyName,
            String searchPropertyName
    ) {
        putSearchProperty(properties, searchPropertyName, parsedModel.entities().stream()
                .filter(entity -> entity.type() == type)
                .map(entity -> entity.properties().get(entityPropertyName))
                .toList());
    }

    private void putAnnotationSearchProperties(LinkedHashMap<String, String> properties, ParsedModel parsedModel) {
        putSearchProperty(properties, "resourceUri", parsedModel.annotations().stream()
                .flatMap(annotation -> annotation.resources().stream())
                .map(ParsedAnnotationResource::uri)
                .toList());
        putSearchProperty(properties, "resourceText", parsedModel.annotations().stream()
                .map(annotation -> annotation.properties().get("resourceText"))
                .toList());
        putSearchProperty(properties, "nonRdf", parsedModel.annotations().stream()
                .map(annotation -> annotation.properties().get("nonRdf"))
                .toList());
        putSearchProperty(properties, "keywords", keywordTokens(parsedModel));
    }

    /**
     * CML-3: the keywords a CellML external cmeta put on its annotation are a fact about the model, and
     * the annotation node is in no full-text index. They are aggregated here so the model index can
     * find them: every annotation's {@code keywords} value is split on whitespace, trimmed, blank
     * tokens dropped, de-duplicated in document order and joined with one space, matching
     * {@code CellmlExternalCmeta.keywordsText}. The annotation keeps its own property untouched.
     */
    private static List<String> keywordTokens(ParsedModel parsedModel) {
        var tokens = new ArrayList<String>();
        parsedModel.annotations().forEach(annotation -> {
            var keywords = annotation.properties().get("keywords");
            if (keywords == null || keywords.isBlank()) {
                return;
            }
            for (var token : keywords.trim().split("\\s+")) {
                if (!token.isBlank() && !tokens.contains(token)) {
                    tokens.add(token);
                }
            }
        });
        return tokens;
    }

    private String entityKey(ParsedModelEntity entity) {
        return entity.type().name().toLowerCase() + ":" + entity.id();
    }

    private String publicationKey(ParsedPublication publication, int index) {
        if (publication.pubId() != null && !publication.pubId().isBlank()) {
            return "publication:" + publication.pubId();
        }
        return "publication:" + index;
    }

    private String personKey(ParsedPerson person) {
        return "person:" + person.givenName() + ":" + person.familyName() + ":" + person.email();
    }

    private Set<String> labels(EntityType type) {
        return switch (type) {
            case COMPARTMENT -> Set.of("SBML_COMPARTMENT");
            case SPECIES -> Set.of("SBML_SPECIES");
            case REACTION -> Set.of("SBML_REACTION");
            case PARAMETER -> Set.of("SBML_PARAMETER");
            case RULE -> Set.of("SBML_RULE");
            case FUNCTION -> Set.of("SBML_FUNCTION");
            case EVENT -> Set.of("SBML_EVENT");
            case COMPONENT -> Set.of("CELLML_COMPONENT");
            case VARIABLE -> Set.of("CELLML_VARIABLE");
            case CELLML_REACTION -> Set.of("CELLML_REACTION");
            case CELLML_IMPORT -> Set.of("CELLML_IMPORT");
            case CELLML_IMPORTED_COMPONENT -> Set.of("CELLML_IMPORTED_COMPONENT");
            case SEDML_MODELREFERENCE -> Set.of("SEDML_MODELREFERENCE");
            case SEDML_SIMULATION -> Set.of("SEDML_SIMULATION");
            case SEDML_TASK -> Set.of("SEDML_TASK");
            case SEDML_DATAGENERATOR -> Set.of("SEDML_DATAGENERATOR");
            case SEDML_VARIABLE -> Set.of("SEDML_VARIABLE");
            case SEDML_OUTPUT -> Set.of("SEDML_OUTPUT");
            case SEDML_CURVE -> Set.of("SEDML_CURVE");
            case SEDML_SURFACE -> Set.of("SEDML_SURFACE");
            case SEDML_DATASET -> Set.of("SEDML_DATASET");
            case SEDML_RANGE -> Set.of("SEDML_RANGE");
            case SEDML_CHANGE -> Set.of("SEDML_CHANGE");
            case UNIT -> Set.of("UNIT");
        };
    }

    private String hasRelationship(EntityType type) {
        return switch (type) {
            case COMPARTMENT -> "HAS_COMPARTMENT";
            case SPECIES -> "HAS_SPECIES";
            case REACTION -> "HAS_REACTION";
            case PARAMETER -> "HAS_PARAMETER";
            case RULE -> "HAS_RULE";
            case FUNCTION -> "HAS_FUNCTION";
            case EVENT -> "HAS_EVENT";
            case COMPONENT -> "HAS_COMPONENT";
            case VARIABLE -> "HAS_VARIABLE";
            case CELLML_REACTION -> "HAS_REACTION";
            case CELLML_IMPORT -> "HAS_IMPORT";
            case CELLML_IMPORTED_COMPONENT -> "HAS_IMPORTED_COMPONENT";
            case SEDML_MODELREFERENCE -> "HAS_MODELREFERENCE";
            case SEDML_SIMULATION -> "HAS_SIMULATION";
            case SEDML_TASK -> "HAS_TASK";
            case SEDML_DATAGENERATOR -> "HAS_DATAGENERATOR";
            case SEDML_VARIABLE -> "HAS_VARIABLE";
            case SEDML_OUTPUT -> "HAS_OUTPUT";
            case SEDML_CURVE -> "HAS_CURVE";
            case SEDML_SURFACE -> "HAS_SURFACE";
            case SEDML_DATASET -> "HAS_DATASET";
            case SEDML_RANGE -> "HAS_RANGE";
            case SEDML_CHANGE -> "HAS_CHANGE";
            case UNIT -> "USES_UNIT";
        };
    }

    private String hasParticipantRelationship(Role role) {
        return switch (role) {
            case REACTANT -> "HAS_REACTANT";
            case PRODUCT -> "HAS_PRODUCT";
            case MODIFIER -> "HAS_MODIFIER";
        };
    }

    private String isParticipantRelationship(Role role) {
        return switch (role) {
            case REACTANT -> "IS_REACTANT";
            case PRODUCT -> "IS_PRODUCT";
            case MODIFIER -> "IS_MODIFIER";
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * The external version label sits on the document and on the model, so both a lookup and a
     * search result can name the version. An absent label writes no property at all, exactly like
     * legacy {@code SBMLExtractor}, which guarded on {@code versionID != null}.
     */
    private void putVersionId(Map<String, String> properties, String versionId) {
        if (versionId != null) {
            properties.put("versionId", versionId);
        }
    }

    private String relationshipType(String qualifier) {
        if (qualifier == null || qualifier.isBlank()) {
            return "HAS_RESOURCE";
        }
        return qualifier.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /**
     * The MIRIAM URI of a SED-ML algorithm term, or empty when the attribute is blank or not a KISAO
     * id. The local id is rendered as {@code KISAO_<id>}, the form identifiers.org uses, so the two
     * id spellings cannot produce two nodes.
     */
    private Optional<String> kisaoUri(String rawKisaoId) {
        if (rawKisaoId == null || rawKisaoId.isBlank()) {
            return Optional.empty();
        }
        return AnnotationPrefixCatalogue.standard()
                .termKey("biomodels.kisao", rawKisaoId)
                .map(key -> "urn:miriam:biomodels.kisao:KISAO_" + key.id());
    }
}

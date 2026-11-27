package org.masymos.core.extract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ParsedOntologyGraphMapper {
    public ExtractedModelGraph toGraph(ParsedOntology ontology) {
        var nodes = new ArrayList<ExtractedGraphNode>();
        var relationships = new ArrayList<ExtractedGraphRelationship>();

        var documentProperties = new LinkedHashMap<String, String>();
        documentProperties.put("fileId", ontology.reference().fileId());
        documentProperties.put("sourceUri", ontology.reference().sourceUri().toString());
        documentProperties.put("modelType", ontology.reference().modelType().name());
        documentProperties.putAll(ontology.properties());

        nodes.add(new ExtractedGraphNode("document", Set.of("Document"), Map.copyOf(documentProperties)));
        nodes.add(new ExtractedGraphNode("model", Set.of("Model", "OWL_MODEL"), Map.of(
                "modelId", ontology.ontologyLabel(),
                "name", ontology.ontologyLabel(),
                "ontology", ontology.ontologyLabel()
        )));
        relationships.add(new ExtractedGraphRelationship("document", "HAS_MODEL", "model", Map.of()));

        ontology.terms().forEach(term -> {
            var key = termKey(term.id());
            nodes.add(new ExtractedGraphNode(key, Set.of(ontology.ontologyLabel()), term.properties()));
            relationships.add(new ExtractedGraphRelationship("model", "HAS_ONTOLOGY_TERM", key, Map.of()));
            relationships.add(new ExtractedGraphRelationship(key, "BELONGS_TO", "model", Map.of()));
        });

        ontology.relationships().forEach(relationship -> relationships.add(new ExtractedGraphRelationship(
                termKey(relationship.startId()),
                relationship.type(),
                termKey(relationship.endId()),
                relationship.properties()
        )));

        return new ExtractedModelGraph(ontology.reference(), List.copyOf(nodes), List.copyOf(relationships));
    }

    private static String termKey(String id) {
        return "ontology-term:" + id;
    }
}

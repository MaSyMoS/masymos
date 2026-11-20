package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class ParsedOntologyGraphMapperTest {
    @Test
    void mapsOntologyToLegacyCompatibleGraphShape() {
        var graph = new ParsedOntologyGraphMapper().toGraph(new ParsedOntology(
                new ModelReference("sbo.owl", URI.create("https://example.org/sbo.owl"), ModelType.OWL),
                "SBOOntology",
                "SBOOntology",
                Map.of("ontology.terms", "2"),
                List.of(
                        new ParsedOntologyTerm("owl:SBOOntology", Map.of("id", "owl:SBOOntology", "name", "SBOOntology")),
                        new ParsedOntologyTerm("SBO_0000001", Map.of("id", "SBO_0000001", "name", "entity"))
                ),
                List.of(new ParsedOntologyRelationship("SBO_0000001", "isA", "owl:SBOOntology", Map.of()))
        ));

        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("model") && node.labels().contains("OWL_MODEL")
        ));
        assertTrue(graph.nodes().stream().anyMatch(node ->
                node.key().equals("ontology-term:SBO_0000001") && node.labels().contains("SBOOntology")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.type().equals("HAS_ONTOLOGY_TERM")
                        && relationship.endKey().equals("ontology-term:SBO_0000001")
        ));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.type().equals("isA")
                        && relationship.startKey().equals("ontology-term:SBO_0000001")
                        && relationship.endKey().equals("ontology-term:owl:SBOOntology")
        ));
    }
}

package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * OPN-2 (ruled 2026-11-26: {@code comp} now, {@code multi} deferred): a composed Level 3 document's
 * submodels and replaced elements are read into the parsed model, so the composition is searchable.
 * The reader emits one {@code SBML_SUBMODEL} entity per submodel, a {@code COMPOSED_FROM} edge from the
 * model node to each, and a {@code REPLACES} edge per replaced element. Reference-only: nothing is
 * resolved across documents ({@code OPN-3}).
 */
class SbmlCompTest {
    private static final String FIXTURE = "sbml-packages/sbml-l3v1-comp-submodels.xml";

    @Test
    void readsOneSubmodelEntityFromTheFixture() throws Exception {
        var parsed = parse(FIXTURE);

        assertEquals(
                List.of("bare", "sub"),
                parsed.entities().stream()
                        .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SBML_SUBMODEL)
                        .map(ParsedModelEntity::id)
                        .sorted()
                        .toList());
    }

    @Test
    void emitsOneComposedFromAndTwoReplacesEdgesWithTheDocumentProperties() throws Exception {
        var graph = new ParsedModelGraphMapper().toGraph(parse(FIXTURE));

        var composedFrom = graph.relationships().stream()
                .filter(relationship -> relationship.type().equals("COMPOSED_FROM"))
                .toList();
        assertEquals(2, composedFrom.size(), composedFrom.toString());
        composedFrom.forEach(relationship -> {
            assertEquals("model", relationship.startKey(), relationship.toString());
            assertTrue(relationship.endKey().startsWith("sbml_submodel:"), relationship.toString());
        });
        // The edge is the mapper's own model-to-child edge and carries no properties, like every other
        // HAS_* edge; the modelRef is a fact about the submodel and lives on its node.
        var submodel = graph.nodes().stream()
                .filter(node -> node.key().equals("sbml_submodel:sub"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no submodel node in " + graph.nodes()));
        assertEquals("MODEL0000000001", submodel.properties().get("modelRef"), submodel.toString());

        var replaces = graph.relationships().stream()
                .filter(relationship -> relationship.type().equals("REPLACES"))
                .toList();
        assertEquals(2, replaces.size(), replaces.toString());
        replaces.forEach(relationship -> {
            assertEquals("sbml_submodel:sub", relationship.startKey(), relationship.toString());
        });
        assertEquals(
                Set.of("compartment:cell", "species:s1"),
                replaces.stream().map(ExtractedGraphRelationship::endKey).collect(java.util.stream.Collectors.toSet()),
                replaces.toString());
        var cell = replaces.stream()
                .filter(relationship -> relationship.endKey().equals("compartment:cell"))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of("idRef", "cell", "portRef", "cellPort", "conversionFactor", "k_cf"), cell.properties());
        var species = replaces.stream()
                .filter(relationship -> relationship.endKey().equals("species:s1"))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of("idRef", "s1"), species.properties());

        assertTrue(
                graph.nodes().stream().anyMatch(node -> node.key().equals("sbml_submodel:sub")
                        && node.labels().equals(Set.of("SBML_SUBMODEL"))),
                graph.nodes().toString());
    }

    /**
     * The other half of the guard: a document that declares no {@code comp} package yields exactly the
     * entities and relationships it yielded before {@code comp} was read. The expectation is inline -
     * the keys this fixture produced on 2026-11-27 - so a change that adds an entity or an edge to a
     * plain Level 3 document fails here instead of quietly widening every document's graph.
     */
    @Test
    void aDocumentWithoutCompYieldsTheSameEntityAndRelationshipKeys() throws Exception {
        var graph = new ParsedModelGraphMapper().toGraph(parse("sbml-packages/sbml-l3v1-fbc-associations.xml"));

        var entityKeys = graph.nodes().stream()
                .map(ExtractedGraphNode::key)
                .filter(key -> !key.equals("document") && !key.equals("model"))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertEquals(
                new TreeSet<>(Set.of("compartment:c", "species:s1", "reaction:R1", "reaction:R2",
                        "sbml_gene_product:G1", "sbml_gene_product:G2")),
                entityKeys);

        var relationshipKeys = graph.relationships().stream()
                .map(relationship -> relationship.startKey() + "-" + relationship.type() + "-" + relationship.endKey())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        assertTrue(relationshipKeys.stream().noneMatch(key -> key.contains("COMPOSED_FROM")), relationshipKeys.toString());
        assertTrue(relationshipKeys.stream().noneMatch(key -> key.contains("REPLACES")), relationshipKeys.toString());
        assertTrue(relationshipKeys.contains("reaction:R1-ASSOCIATED_WITH-sbml_gene_product:G1"), relationshipKeys.toString());
    }

    /**
     * Reading {@code comp} means the package can no longer be reported as a dropped namespace. The
     * measurement behind the rule: a document whose {@code comp} subtrees the strip retry removes still
     * reads the composition, so reporting {@code comp} as dropped would claim a loss that did not
     * happen. The marker itself is untouched for every other namespace, and {@code fbc}'s {@code not}
     * loss still counts.
     */
    @Test
    void aComposedDocumentNoLongerReportsCompAsADroppedNamespace() throws Exception {
        var parsed = parse(FIXTURE);

        assertFalse(
                parsed.properties().getOrDefault("sbml.droppedNamespaces", "").contains("comp"),
                parsed.properties().toString());
    }

    private static ParsedModel parse(String fixture) throws Exception {
        var reference = new ModelReference(
                Path.of(fixture).getFileName().toString(),
                URI.create("https://example.org/" + fixture),
                ModelType.SBML);
        try (InputStream stream = SbmlCompTest.class.getClassLoader().getResourceAsStream(fixture)) {
            return new SbmlModelParser().parse(new ModelSource(reference), stream);
        }
    }
}

package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * Parses a real CellML 2.0 model, unmodified from the libcellml test resources
 * (Ohara-Rudy 2011 cardiac electrophysiology, downloaded 2026-11-11 from
 * {@code https://raw.githubusercontent.com/cellml/libcellml/main/tests/resources/Ohara_Rudy_2011.cellml}).
 *
 * <p>CellML 2.0 puts {@code component_1}/{@code component_2} on the {@code <connection>} element
 * instead of inside {@code <map_components>}; a synthetic fixture proved that, this file proves it
 * on a model that was written by real tooling.
 */
class CellmlRealWorldTest {

    private static final String FIXTURE = "cellml-real-world/Ohara_Rudy_2011.cellml";
    private static final String CELLML_2_NAMESPACE = "http://www.cellml.org/cellml/2.0#";

    @Test
    void parsesRealCellml20Model() throws Exception {
        var parsed = parse();

        assertEquals("2.0", parsed.properties().get("cellml.version"));
        assertEquals(CELLML_2_NAMESPACE, parsed.properties().get("cellml.namespace"));
        assertFalse(parsed.entities().isEmpty(), "real CellML model must yield entities");
        assertTrue(parsed.entities().stream().anyMatch(entity -> entity.type() == EntityType.COMPONENT),
                "real CellML model must yield components");
        assertTrue(parsed.entities().stream().anyMatch(entity -> entity.type() == EntityType.VARIABLE),
                "real CellML model must yield variables");
    }

    @Test
    void resolvesCellml20ConnectionsOnRealModel() throws Exception {
        var parsed = parse();

        var connections = parsed.entityRelationships().stream()
                .filter(relationship -> "IS_CONNECTED_TO".equals(relationship.type()))
                .toList();
        assertFalse(connections.isEmpty(), "CellML 2.0 connections must not be dropped");
    }

    @Test
    void recordsUnitsOnRealCellml20Variables() throws Exception {
        var parsed = parse();

        var variablesWithUnits = parsed.entities().stream()
                .filter(entity -> entity.type() == EntityType.VARIABLE)
                .filter(entity -> !entity.properties().getOrDefault("units", "").isBlank())
                .count();
        assertTrue(variablesWithUnits > 0, "CellML 2.0 requires units on variables");
    }

    @Test
    void realCellmlModelProducesReferentiallyIntactGraph() throws Exception {
        var graph = new ParsedModelGraphMapper().toGraph(parse());
        var nodeKeys = graph.nodes().stream().map(ExtractedGraphNode::key).collect(Collectors.toSet());

        var dangling = graph.relationships().stream()
                .filter(relationship -> !nodeKeys.contains(relationship.startKey())
                        || !nodeKeys.contains(relationship.endKey()))
                .map(relationship -> relationship.startKey() + "-[" + relationship.type() + "]->" + relationship.endKey())
                .toList();
        assertTrue(dangling.isEmpty(), "dangling relationships: " + dangling);

        var connected = graph.relationships().stream()
                .flatMap(relationship -> Stream.of(relationship.startKey(), relationship.endKey()))
                .collect(Collectors.toSet());
        var orphans = nodeKeys.stream().filter(key -> !connected.contains(key)).toList();
        assertTrue(orphans.isEmpty(), "orphan nodes: " + orphans);
    }

    private static ParsedModel parse() throws Exception {
        var source = new ModelSource(new ModelReference(
                "Ohara_Rudy_2011.cellml",
                URI.create("https://raw.githubusercontent.com/cellml/libcellml/main/tests/resources/Ohara_Rudy_2011.cellml"),
                ModelType.CELLML
        ));
        try (InputStream stream = CellmlRealWorldTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource: " + FIXTURE);
            }
            return new CellmlModelParser().parse(source, stream);
        }
    }
}

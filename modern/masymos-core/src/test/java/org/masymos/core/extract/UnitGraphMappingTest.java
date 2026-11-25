package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.units.CanonicalUnit;
import org.masymos.core.units.UnitCatalogue;
import org.masymos.core.units.UnitDialect;
import org.masymos.core.units.UnitEntityBuilder;
import org.masymos.core.units.UnitExpression;
import org.masymos.core.units.UnitResolver;
import org.masymos.core.units.UnitTerm;

/**
 * The unit half of the mapper: a unit becomes a shared {@code UNIT} node, the model points at it
 * with {@code USES_UNIT}, and the unit is not owned by the document that happened to mention it.
 */
class UnitGraphMappingTest {

    @Test
    void aUnitEntityBecomesASharedNodeWithItsComposition() {
        var reference = new ModelReference("demo.cellml", URI.create("file:///demo.cellml"), ModelType.CELLML);
        var graph = new ParsedModelGraphMapper()
                .toGraph(new ParsedModel(
                        reference,
                        "demo",
                        "demo",
                        Map.of(),
                        List.of(
                                UnitEntityBuilder.entity(nanomolar()),
                                new ParsedModelEntity(
                                        ParsedModelEntity.EntityType.VARIABLE, "main/c", "c", Map.of("units", "nanomolar"))),
                        List.of(),
                        List.of(),
                        List.of()));

        assertTrue(
                graph.nodes().stream()
                        .anyMatch(node -> node.labels().equals(Set.of("UNIT"))
                                && "unit:0.000001*metre^-3*mole^1".equals(node.properties().get("key"))),
                graph.nodes().toString());
        assertTrue(
                graph.relationships().stream()
                        .anyMatch(relationship -> relationship.type().equals("USES_UNIT")
                                && relationship.startKey().equals("model")
                                && relationship.endKey().equals("unit:unit:0.000001*metre^-3*mole^1")),
                graph.relationships().toString());
        assertFalse(
                graph.relationships().stream()
                        .anyMatch(relationship -> relationship.type().equals("BELONGS_TO")
                                && relationship.startKey().equals("unit:unit:0.000001*metre^-3*mole^1")),
                "a shared unit must not be owned by the model that mentioned it");
    }

    private static CanonicalUnit nanomolar() {
        return new UnitResolver(UnitCatalogue.builtIns())
                .resolve(
                        new UnitExpression(
                                "nanomolar",
                                List.of(new UnitTerm("mole", "1", "0.000000001"), new UnitTerm("litre", "-1", "1")),
                                "1",
                                "0"),
                        UnitDialect.CELLML_20);
    }
}

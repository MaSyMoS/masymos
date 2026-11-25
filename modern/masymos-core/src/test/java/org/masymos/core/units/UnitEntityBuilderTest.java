package org.masymos.core.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;

class UnitEntityBuilderTest {

    @Test
    void aUnitEntityCarriesItsIdentityAndItsSpellings() {
        var entity = UnitEntityBuilder.entity(nanomolar());

        assertEquals(EntityType.UNIT, entity.type());
        assertEquals("unit:0.000001*metre^-3*mole^1", entity.id());
        assertEquals("nanomolar", entity.name());
        assertEquals("nanomolar", entity.properties().get("display"));
        assertEquals("nanomolar", entity.properties().get("names"));
        assertEquals("derived", entity.properties().get("kind"));
        assertEquals("metre^-3*mole^1", entity.properties().get("dimension"));
        assertEquals("0.000001", entity.properties().get("multiplier"));
    }

    @Test
    void entitiesAreDeduplicatedByKey() {
        var entities = UnitEntityBuilder.entities(List.of(nanomolar(), nanomolar(), metre()));

        assertEquals(2, entities.size());
    }

    @Test
    void compositionIsOneEdgePerNamedComponent() {
        var edges = UnitEntityBuilder.composition(List.of(nanomolar()));

        assertEquals(2, edges.size());
        assertTrue(edges.stream().anyMatch(edge -> edge.type().equals("COMPOSED_OF")
                && edge.startEntityKey().equals("UNIT:unit:0.000001*metre^-3*mole^1")
                && edge.endEntityKey().equals("UNIT:base:mole")
                && edge.properties().get("exponent").equals("1")));
        assertTrue(edges.stream().anyMatch(edge -> edge.endEntityKey().equals("UNIT:unit:0.001*metre^3")
                && edge.properties().get("exponent").equals("-1")));
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

    private static CanonicalUnit metre() {
        return UnitCatalogue.builtIns().builtIn("metre", UnitDialect.CELLML_20).orElseThrow();
    }
}

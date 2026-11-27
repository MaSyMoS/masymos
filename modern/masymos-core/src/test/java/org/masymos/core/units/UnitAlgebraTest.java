package org.masymos.core.units;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnitAlgebraTest {
    @Test
    void combinesExponentsOfTheSameBaseAndDropsDimensionless() {
        var expression = new UnitExpression(
                "demo",
                List.of(
                        new UnitTerm("metre", "1", "1"),
                        new UnitTerm("metre", "1", "1"),
                        new UnitTerm("dimensionless", "1", "1")),
                "1",
                "0");

        var unit = new UnitResolver(UnitCatalogue.builtIns()).resolve(expression, UnitDialect.CELLML_20);

        assertEquals("unit:1*metre^2", unit.key());
        assertEquals("metre^2", unit.dimension());
    }

    @Test
    void normalisesDecimalSpellingsToOneKey() {
        var a = UnitCatalogue.normalise("1e-9");
        var b = UnitCatalogue.normalise("0.000000001");
        var c = UnitCatalogue.normalise("1.0E-9");

        assertEquals(a, b);
        assertEquals(b, c);
    }

    @Test
    void keepsTheDeclaredComponentsForTheClosure() {
        var expression = new UnitExpression(
                "nanomolar",
                List.of(new UnitTerm("mole", "1", "0.000000001"), new UnitTerm("litre", "-1", "1")),
                "1",
                "0");

        var unit = new UnitResolver(UnitCatalogue.builtIns()).resolve(expression, UnitDialect.CELLML_20);

        assertEquals(
                List.of("base:mole", "unit:0.001*metre^3"),
                unit.components().stream().map(UnitComponent::key).sorted().toList());
        assertEquals("unit:0.000001*metre^-3*mole^1", unit.key());
    }

    /**
     * {@code UNIT-003}: a declared unit is flattened from its definition graph, never from its
     * {@code dimension} string - that string is derived output, so reading it back is a second source
     * of identity, and a producer that writes it in another spelling must not be able to change a key
     * or make a resolve throw.
     */
    @Test
    void aDeclaredUnitIsFlattenedFromItsComponents() {
        var resolver = new UnitResolver(UnitCatalogue.builtIns());
        var use = new UnitExpression("twice_u", List.of(new UnitTerm("u", "1", "1")), "1", "0");

        var declared = new CanonicalUnit("declared-base:u", "u", List.of("u"), "declared-base", "metre^2", "2",
                null, List.of(new UnitComponent("base:metre", "2", "1")));

        assertEquals("unit:2*metre^2",
                resolver.resolve(use, UnitDialect.CELLML_20, Map.of("u", declared)).key());

        // The negative: a composer's bug in the dimension string. Identity follows the components, so
        // the resolve answers the same key instead of throwing "not a base dimension".
        var buggyDimension = new CanonicalUnit("declared-base:u", "u", List.of("u"), "declared-base",
                "unit:2*metre^2", "2", null, List.of(new UnitComponent("base:metre", "2", "1")));

        assertEquals("unit:2*metre^2",
                resolver.resolve(use, UnitDialect.CELLML_20, Map.of("u", buggyDimension)).key());
    }
}

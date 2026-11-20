package org.masymos.core.units;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
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
}

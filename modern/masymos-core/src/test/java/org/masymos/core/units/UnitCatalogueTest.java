package org.masymos.core.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UnitCatalogueTest {
    private final UnitCatalogue catalogue = UnitCatalogue.builtIns();

    @Test
    void reducesCellmlBuiltInsPerTheSpecificationTable() {
        assertEquals(
                "unit:1*metre^2*second^-2",
                catalogue.builtIn("gray", UnitDialect.CELLML_20).orElseThrow().key());
        assertEquals(
                "unit:0.001*metre^3",
                catalogue.builtIn("litre", UnitDialect.CELLML_20).orElseThrow().key());
        assertEquals(
                "base:kilogram",
                catalogue.builtIn("kilogram", UnitDialect.CELLML_20).orElseThrow().key());
    }

    @Test
    void reducesSbmlKindsIncludingItsPseudoUnits() {
        assertEquals(
                "unit:1*kilogram^1*metre^2*second^-2",
                catalogue.builtIn("joule", UnitDialect.SBML_3).orElseThrow().key());
        assertEquals(
                "base:dimensionless",
                catalogue.builtIn("item", UnitDialect.SBML_3).orElseThrow().key());
        assertEquals(
                "unit:602214179000000000000000*dimensionless",
                catalogue.builtIn("avogadro", UnitDialect.SBML_3).orElseThrow().key());
        assertTrue(catalogue.builtIn("avogadro", UnitDialect.SBML_2).isEmpty());
    }

    @Test
    void keepsOffsetsOutOfTheDimension() {
        var celsius = catalogue.builtIn("celsius", UnitDialect.CELLML_10_11).orElseThrow();

        assertEquals("base:kelvin", celsius.components().getFirst().key());
        assertEquals("273.15", celsius.offset());
    }

    @Test
    void l1KnowsOnlyItsOwnNamesAndIsCaseInsensitive() {
        assertEquals(
                "base:metre",
                catalogue.builtIn("METER", UnitDialect.SBML_1).orElseThrow().key());
        assertTrue(catalogue.builtIn("avogadro", UnitDialect.SBML_1).isEmpty());
        assertEquals(
                "unit:1*kilogram^1*metre^2*second^-2",
                catalogue.builtIn("joule", UnitDialect.SBML_1).orElseThrow().key());
    }
}

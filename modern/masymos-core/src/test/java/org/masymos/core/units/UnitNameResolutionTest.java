package org.masymos.core.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnitNameResolutionTest {
    private final UnitNameResolution resolution = new UnitNameResolution(UnitCatalogue.builtIns());

    @Test
    void resolvesSpellingVariantsToOneKey() {
        assertEquals(List.of("base:metre"), resolution.keysFor("meter"));
        assertEquals(List.of("base:metre"), resolution.keysFor("metre"));
        assertEquals(List.of("base:metre"), resolution.keysFor("m"));
        assertEquals(List.of("unit:0.001*metre^3"), resolution.keysFor("liter"));
    }

    @Test
    void resolvesPrefixAndSymbolForms() {
        assertEquals(List.of("unit:0.01*metre^1"), resolution.keysFor("cm"));
        assertEquals(List.of("unit:1000*metre^1"), resolution.keysFor("kilometer"));
        assertEquals(List.of("unit:0.000001*metre^1"), resolution.keysFor("um"));
        assertEquals(List.of("unit:0.000001*metre^1"), resolution.keysFor("µm"));
        assertEquals(List.of("unit:0.000001*metre^-3*mole^1"), resolution.keysFor("nanomolar"));
    }

    @Test
    void leavesAnUnknownNameUnresolvedInsteadOfGuessing() {
        assertTrue(resolution.keysFor("furlong_per_fortnight").isEmpty());
        assertTrue(resolution.keysFor("").isEmpty());
    }
}

package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** CML-1: the unit names a model query narrows by are trimmed, blank-free and immutable. */
class ModelQueryRequestUnitsTest {

    @Test
    void dropsBlankUnitNamesAndKeepsTheRest() {
        var request = new ModelQueryRequest("calcium", List.of(), 10, null, false, null, List.of(" ", "metre", " cm "));

        assertEquals(List.of("metre", "cm"), request.units());
    }

    @Test
    void anAbsentUnitListMeansNoNarrowing() {
        assertTrue(new ModelQueryRequest("calcium", List.of(), 10).units().isEmpty());
        assertTrue(new ModelQueryRequest("x", List.of(), 10, null, false, null, null).units().isEmpty());
    }
}

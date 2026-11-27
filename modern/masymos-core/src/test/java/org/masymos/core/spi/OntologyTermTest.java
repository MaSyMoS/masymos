package org.masymos.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OntologyTermTest {
    @Test
    void copiesThePropertyMapSoLaterMutationCannotLeakIntoTheResult() {
        var properties = new HashMap<String, String>();
        properties.put("name", "A");

        var term = new OntologyTerm("A", properties);
        properties.put("name", "changed");

        assertEquals("A", term.properties().get("name"));
        assertThrows(UnsupportedOperationException.class, () -> term.properties().put("x", "y"));
    }

    @Test
    void treatsANullPropertyMapAsEmpty() {
        assertEquals(Map.of(), new OntologyTerm("A", null).properties());
    }
}

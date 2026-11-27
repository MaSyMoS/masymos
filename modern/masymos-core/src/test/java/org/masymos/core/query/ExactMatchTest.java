package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactMatchTest {
    @Test
    void keepsPropertyAndCopiesTheValues() {
        var match = new ExactMatch("p", List.of("v", "v2"));

        assertEquals("p", match.property());
        assertEquals(List.of("v", "v2"), match.values());
    }

    @Test
    void rejectsAnEmptyValueList() {
        assertThrows(IllegalArgumentException.class, () -> new ExactMatch("p", List.of()));
    }

    @Test
    void rejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> new ExactMatch("p", List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> new ExactMatch("p", List.of("v", "")));
    }

    @Test
    void rejectsNullValuesAndANullList() {
        var withNull = new ArrayList<String>();
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> new ExactMatch("p", withNull));
        assertThrows(IllegalArgumentException.class, () -> new ExactMatch("p", null));
    }

    @Test
    void hasNoWhitespaceConcernsForValuesWithInnerSpaces() {
        var match = new ExactMatch("p", List.of("a b"));

        assertEquals(List.of("a b"), match.values());
        assertFalse(match.values().isEmpty());
    }
}

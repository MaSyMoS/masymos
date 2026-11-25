package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ExactTextTest {
    @Test
    void trimsAndLowercasesWithTheRootLocale() {
        assertEquals("abc", ExactText.lowercase("  ABC "));
        assertEquals("stra\u00dfe", ExactText.lowercase("Stra\u00dfe"));
    }

    @Test
    void returnsNullForNull() {
        assertNull(ExactText.lowercase(null));
        assertNull(ExactText.identity(null));
    }

    @Test
    void trimsButPreservesTheCaseForTheAnnotationUri() {
        assertEquals("Http://X/A", ExactText.identity(" Http://X/A "));
        assertEquals("", ExactText.identity("   "));
    }
}

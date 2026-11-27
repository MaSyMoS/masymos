package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResolverModeTest {

    @Test
    void everyModeHasItsWireValue() {
        assertEquals("providers", ResolverMode.PROVIDERS.wire());
        assertEquals("direct", ResolverMode.DIRECT.wire());
        assertEquals("off", ResolverMode.OFF.wire());
    }

    @Test
    void fromWireAcceptsEveryModeCaseInsensitively() {
        for (var mode : ResolverMode.values()) {
            assertEquals(mode, ResolverMode.fromWire(mode.wire()));
            assertEquals(mode, ResolverMode.fromWire(mode.wire().toUpperCase()));
            assertEquals(mode, ResolverMode.fromWire("  " + mode.wire() + " "));
        }
        assertEquals(ResolverMode.PROVIDERS, ResolverMode.fromWire("Providers"));
    }

    @Test
    void absentValueMeansDirect() {
        assertEquals(ResolverMode.DIRECT, ResolverMode.fromWire(null));
        assertEquals(ResolverMode.DIRECT, ResolverMode.fromWire(""));
        assertEquals(ResolverMode.DIRECT, ResolverMode.fromWire("   "));
    }

    @Test
    void unknownValueIsRejectedNamingTheAcceptedValues() {
        var exception = assertThrows(IllegalArgumentException.class, () -> ResolverMode.fromWire("cached"));
        var message = exception.getMessage();
        assertTrue(message.contains("providers"), message);
        assertTrue(message.contains("direct"), message);
        assertTrue(message.contains("off"), message);
    }
}

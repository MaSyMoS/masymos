package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PageCursorTest {
    @Test
    void roundTripsAScoreAndAnIdentity() {
        var cursor = new PageCursor(1.5, "a|b%c", "f1");

        var decoded = PageCursor.decode(cursor.encode());

        assertEquals(1.5, decoded.score());
        assertEquals("a|b%c", decoded.identity(), "the separators inside an identity must survive");
        assertEquals("f1", decoded.fingerprint());
    }

    @Test
    void refusesACursorFromAnotherRequest() {
        var encoded = new PageCursor(1.0, "file-1", "aaaa").encode();

        var refused = assertThrows(
                IllegalArgumentException.class, () -> PageCursor.decode(encoded).requireFingerprint("bbbb"));

        assertEquals(
                "cursor belongs to a different request; omit it to start at the beginning", refused.getMessage());
    }

    @Test
    void refusesSomethingThatIsNotACursor() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decode("not-a-cursor"));
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decode(""));
        assertThrows(IllegalArgumentException.class, () -> PageCursor.decode(null));
    }

    @Test
    void theFingerprintIsStableAndDiscriminating() {
        assertEquals(PageCursor.fingerprint("a", "b"), PageCursor.fingerprint("a", "b"));
        assertNotEquals(PageCursor.fingerprint("a", "b"), PageCursor.fingerprint("a", "c"));
        assertNotEquals(PageCursor.fingerprint("a", "b"), PageCursor.fingerprint("ab", ""));
    }
}

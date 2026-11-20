package org.masymos.core.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ContentDigestTest {
    @Test
    void theLabelIsThePrefixPlusSixteenHexCharactersOfTheSha256() {
        var label = ContentDigest.versionLabel("hello".getBytes(StandardCharsets.UTF_8));

        assertEquals("sha256-2cf24dba5fb0a30e", label);
        assertTrue(label.matches("sha256-[0-9a-f]{16}"), label);
    }

    @Test
    void theLabelIsStableForIdenticalBytesAndChangesWithOneByte() {
        var first = "<sbml/>".getBytes(StandardCharsets.UTF_8);
        var second = "<sbml/>".getBytes(StandardCharsets.UTF_8);
        var changed = "<sbml />".getBytes(StandardCharsets.UTF_8);

        assertEquals(ContentDigest.versionLabel(first), ContentDigest.versionLabel(second));
        assertNotEquals(ContentDigest.versionLabel(first), ContentDigest.versionLabel(changed));
    }

    @Test
    void emptyContentStillGetsALabel() {
        assertEquals("sha256-e3b0c44298fc1c14", ContentDigest.versionLabel(new byte[0]));
    }

    @Test
    void nullContentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ContentDigest.versionLabel(null));
    }
}

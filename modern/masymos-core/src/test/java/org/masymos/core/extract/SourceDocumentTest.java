package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceDocumentTest {
    @Test
    void storesAUtf8DocumentByteIdentically() {
        var bytes = "<sbml>Grüße</sbml>".getBytes(StandardCharsets.UTF_8);

        var document = SourceDocument.from(bytes, 1024);

        assertEquals("<sbml>Grüße</sbml>", document.content());
        assertEquals("UTF-8", document.encoding());
        assertEquals(bytes.length, document.originalBytes());
        assertFalse(document.truncated());
        assertArrayEquals(bytes, document.contentBytes());
    }

    @Test
    void keepsBytesThatAreNotUtf8Retrievable() {
        var bytes = new byte[] {(byte) 0xE4, (byte) 0xF6, (byte) 0xFC};

        var document = SourceDocument.from(bytes, 1024);

        assertEquals("ISO-8859-1", document.encoding());
        assertFalse(document.truncated());
        assertArrayEquals(bytes, document.contentBytes());
    }

    @Test
    void truncatesAnOversizedDocumentAtACharacterBoundaryAndRecordsTheOriginalSize() {
        var bytes = "ääää".getBytes(StandardCharsets.UTF_8);

        var document = SourceDocument.from(bytes, 5);

        assertTrue(document.truncated());
        assertEquals(bytes.length, document.originalBytes());
        assertEquals("ää", document.content());
        assertEquals("UTF-8", document.encoding());
        assertArrayEquals("ää".getBytes(StandardCharsets.UTF_8), document.contentBytes());
    }

    @Test
    void storesADocumentOfExactlyTheLimitWithoutTruncating() {
        var bytes = "abcd".getBytes(StandardCharsets.UTF_8);

        var document = SourceDocument.from(bytes, 4);

        assertFalse(document.truncated());
        assertEquals("abcd", document.content());
        assertArrayEquals(bytes, document.contentBytes());
    }

    @Test
    void truncatesBytePreservingContentWithoutSplittingCharacters() {
        var bytes = new byte[] {(byte) 0xE4, (byte) 0xF6};

        var document = SourceDocument.from(bytes, 1);

        assertTrue(document.truncated());
        assertEquals(2, document.originalBytes());
        assertEquals("ISO-8859-1", document.encoding());
        assertArrayEquals(new byte[] {(byte) 0xE4}, document.contentBytes());
    }
}

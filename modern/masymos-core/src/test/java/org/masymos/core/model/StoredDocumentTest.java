package org.masymos.core.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredDocumentTest {
    @Test
    void servesTheStoredContentInItsRecordedEncoding() {
        var document = new StoredDocument(
                "uid-1", "BIOMD1", "2014-01-01", 7L,
                "Grüße", "UTF-8", 8, false, Map.of("submitter", "jj"));

        assertTrue(document.hasContent());
        assertArrayEquals("Grüße".getBytes(StandardCharsets.UTF_8), document.contentBytes());
        assertEquals(8, document.originalBytes());
        assertFalse(document.truncated());
        assertEquals(Map.of("submitter", "jj"), document.metadata());
    }

    @Test
    void reportsAnEmptyCapturedDocumentAsPresent() {
        var document = new StoredDocument("uid-1", "BIOMD1", null, null, "", "UTF-8", 0, false, null);

        assertTrue(document.hasContent());
        assertEquals(0, document.contentBytes().length);
    }

    @Test
    void reportsADocumentWithoutStoredContentAsEmpty() {
        var document = new StoredDocument("uid-1", "BIOMD1", null, null, null, null, 0, false, null);

        assertFalse(document.hasContent());
        assertEquals(0, document.contentBytes().length);
        assertEquals("UTF-8", document.encoding());
        assertEquals(Map.of(), document.metadata());
        assertNull(document.versionId());
    }
}

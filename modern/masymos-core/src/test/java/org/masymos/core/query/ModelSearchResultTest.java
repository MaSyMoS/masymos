package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelSearchResultTest {
    @Test
    void theSixArgumentConstructorHasNoVersions() {
        var result = new ModelSearchResult("u", "m", "n", URI.create("file:///x"), "f", 1.0);

        assertTrue(result.versions().isEmpty());
        assertEquals("u", result.uid());
        assertEquals(1.0, result.score());
    }

    @Test
    void aNullVersionListBecomesEmpty() {
        var result = new ModelSearchResult("u", "m", "n", URI.create("file:///x"), "f", 1.0, null);

        assertTrue(result.versions().isEmpty());
    }

    @Test
    void keepsAndCopiesTheVersions() {
        var versions = new ArrayList<ModelVersionHit>();
        versions.add(new ModelVersionHit("u1", "v1", 0.9));
        var result = new ModelSearchResult("u", "m", "n", URI.create("file:///x"), "f", 0.9, versions);
        versions.clear();

        assertEquals(List.of(new ModelVersionHit("u1", "v1", 0.9)), result.versions());
    }

    @Test
    void aVersionHitKeepsAMissingVersionId() {
        var hit = new ModelVersionHit("u1", null, 0.9);

        assertNull(hit.versionId());
        assertEquals("u1", hit.uid());
    }

    @Test
    void rejectsANullVersionInsideTheList() {
        var versions = new ArrayList<ModelVersionHit>();
        versions.add(null);

        assertThrows(NullPointerException.class,
                () -> new ModelSearchResult("u", "m", "n", URI.create("file:///x"), "f", 1.0, versions));
    }

    @Test
    void theSixArgumentConstructorDefaultsTheRicherComponents() {
        var result = new ModelSearchResult("u", "m", "n", URI.create("file:///x"), "f", 1.0);

        assertNull(result.versionId());
        assertTrue(result.matchedFields().isEmpty());
        assertNull(result.indexSource());
        assertFalse(result.documentStored());
        assertNull(result.documentBytes());
        assertFalse(result.documentTruncated());
    }

    @Test
    void theSevenArgumentConstructorDefaultsTheRicherComponents() {
        var result = new ModelSearchResult(
                "u", "m", "n", URI.create("file:///x"), "f", 1.0, List.of(new ModelVersionHit("u1", "v1", 1.0)));

        assertEquals(List.of(new ModelVersionHit("u1", "v1", 1.0)), result.versions());
        assertNull(result.versionId());
        assertTrue(result.matchedFields().isEmpty());
        assertNull(result.indexSource());
        assertFalse(result.documentStored());
        assertNull(result.documentBytes());
        assertFalse(result.documentTruncated());
    }

    @Test
    void theFullConstructorKeepsEveryRicherComponent() {
        var result = new ModelSearchResult(
                "u", "m", "n", URI.create("file:///x"), "f", 0.5,
                List.of(new ModelVersionHit("u1", "v1", 0.5)),
                "v1",
                List.of(QueryField.NAME, QueryField.KEYWORD),
                "modelFulltextV5",
                true,
                1024L,
                true);

        assertEquals("v1", result.versionId());
        assertEquals(List.of(QueryField.NAME, QueryField.KEYWORD), result.matchedFields());
        assertEquals("modelFulltextV5", result.indexSource());
        assertTrue(result.documentStored());
        assertEquals(1024L, result.documentBytes());
        assertTrue(result.documentTruncated());
        assertEquals(List.of(new ModelVersionHit("u1", "v1", 0.5)), result.versions());
    }

    @Test
    void aNullMatchedFieldListBecomesEmptyAndTheListIsCopied() {
        var fields = new ArrayList<QueryField>();
        fields.add(QueryField.NAME);
        var copied = new ModelSearchResult(
                "u", "m", "n", URI.create("file:///x"), "f", 0.5, null,
                null, fields, "exact", false, null, false);
        fields.clear();
        var nulled = new ModelSearchResult(
                "u", "m", "n", URI.create("file:///x"), "f", 0.5, null,
                null, null, "exact", false, null, false);

        assertEquals(List.of(QueryField.NAME), copied.matchedFields());
        assertTrue(nulled.matchedFields().isEmpty());
    }
}

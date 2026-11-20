package org.masymos.core.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class CombineArchiveUriTest {

    @Test
    void buildsEntryUriFromArchiveUri() {
        var entry = CombineArchiveUri.of(URI.create("file:///data/x.omex"), "models/a.sbml");

        assertEquals("file:///data/x.omex!/models/a.sbml", entry.toString());
        assertTrue(CombineArchiveUri.isArchiveUri(entry));
    }

    @Test
    void stripsLeadingSlashFromEntryPath() {
        var entry = CombineArchiveUri.of(URI.create("file:///data/x.omex"), "/models/a.sbml");

        assertEquals("file:///data/x.omex!/models/a.sbml", entry.toString());
    }

    @Test
    void parsesEntryUriBackIntoSArchiveAndEntry() {
        var parsed = CombineArchiveUri.parse(URI.create("file:///data/x.omex!/sedml/a.sedml"));

        assertTrue(parsed.isPresent());
        assertEquals(URI.create("file:///data/x.omex"), parsed.get().archiveUri());
        assertEquals("sedml/a.sedml", parsed.get().entryPath());
    }

    @Test
    void leavesNonArchiveUrisUnparsed() {
        assertFalse(CombineArchiveUri.isArchiveUri(URI.create("file:///data/x.omex")));
        assertTrue(CombineArchiveUri.parse(URI.create("https://example.org/model.xml")).isEmpty());
        assertTrue(CombineArchiveUri.parse(null).isEmpty());
    }

    /**
     * The reason for the {@code !/} convention: relative SED-ML references inside an archive resolve
     * to sibling entries without any special-casing.
     */
    @Test
    void resolvesRelativeReferencesAgainstTheEntryLocation() {
        var sedml = URI.create("file:///data/x.omex!/sedml/a.sedml");

        // java.net.URI normalises file:/// to file:/ while resolving, so assert the semantics instead
        // of the exact number of slashes.
        var sibling = sedml.resolve("../models/a.sbml");
        assertTrue(sibling.toString().endsWith("!/models/a.sbml"), sibling.toString());
        assertEquals("models/a.sbml", CombineArchiveUri.parse(sibling).orElseThrow().entryPath());

        var sameDirectory = sedml.resolve("model.xml");
        assertTrue(sameDirectory.toString().endsWith("!/sedml/model.xml"), sameDirectory.toString());
        assertEquals("sedml/model.xml", CombineArchiveUri.parse(sameDirectory).orElseThrow().entryPath());
    }

    @Test
    void rejectsBlankEntryPath() {
        assertThrows(CombineArchiveException.class,
                () -> CombineArchiveUri.of(URI.create("file:///data/x.omex"), "  "));
        assertThrows(CombineArchiveException.class,
                () -> CombineArchiveUri.of(null, "models/a.sbml"));
    }
}

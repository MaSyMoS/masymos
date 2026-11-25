package org.masymos.core.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;
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

    /**
     * The literal table of {@code DESIGN-C02}: one identity per real entry, whatever spelling the
     * manifest, a packer or a URI-encoded path uses. Case is kept, because two spellings that differ
     * only in case are two members of a zip.
     */
    @Test
    void normalisesSegmentsDotDotBackslashCaseAndTrailingSlash() {
        var archive = URI.create("file:///data/a.omex");
        var modelsAsbml = "file:///data/a.omex!/models/a.sbml";

        assertEquals(modelsAsbml, CombineArchiveUri.of(archive, "models//a.sbml").toString());
        assertEquals(modelsAsbml, CombineArchiveUri.of(archive, "./models/a.sbml").toString());
        assertEquals(modelsAsbml, CombineArchiveUri.of(archive, "models/sub/../a.sbml").toString());
        assertEquals(modelsAsbml, CombineArchiveUri.of(archive, "models\\a.sbml").toString());
        assertEquals(modelsAsbml, CombineArchiveUri.of(archive, "models\\sub\\..\\a.sbml").toString());
        assertEquals(modelsAsbml, CombineArchiveUri.of(archive, "  models/a.sbml  ").toString());

        // One encoded spelling for one member: a manifest's raw space and the encoded path agree.
        var encoded = "file:///data/a.omex!/models/a%20b.sbml";
        assertEquals(encoded, CombineArchiveUri.of(archive, "models/a b.sbml").toString());
        assertEquals(encoded, CombineArchiveUri.of(archive, "models/a%20b.sbml").toString());

        // Case preserved: Models/x.sbml and models/x.sbml are two files in a zip.
        assertEquals("file:///data/a.omex!/Models/A.sbml",
                CombineArchiveUri.of(archive, "Models/A.sbml").toString());
        // Trailing slash names the directory itself, not an empty segment.
        assertEquals("file:///data/a.omex!/models", CombineArchiveUri.of(archive, "models/").toString());

        // parse hands back the path of() already normalised.
        assertEquals("models/a.sbml",
                CombineArchiveUri.parse(CombineArchiveUri.of(archive, "models\\sub\\..\\a.sbml"))
                        .orElseThrow().entryPath());

        // A nested archive is still split at the first separator only.
        var nested = CombineArchiveUri.of(URI.create("file:///data/a.zip!/b.zip"), "/x.sbml");
        assertEquals("file:///data/a.zip!/b.zip!/x.sbml", nested.toString());
        assertEquals("b.zip!/x.sbml", CombineArchiveUri.parse(nested).orElseThrow().entryPath());
    }

    @Test
    void refusesAnEntryPathThatEscapesItsArchive() {
        var archive = URI.create("file:///data/a.omex");

        assertThrows(CombineArchiveException.class, () -> CombineArchiveUri.of(archive, "../x.sbml"));
        assertThrows(CombineArchiveException.class, () -> CombineArchiveUri.of(archive, "models/../../x.sbml"));
        assertTrue(CombineArchiveUri.normaliseEntryPath("../x.sbml").isEmpty());
        assertTrue(CombineArchiveUri.normaliseEntryPath("models/../../x.sbml").isEmpty());
        assertEquals(Optional.of("x.sbml"), CombineArchiveUri.normaliseEntryPath("/x.sbml"));
    }
}

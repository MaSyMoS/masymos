package org.masymos.core.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The contract between the harvest script and the corpus lane (CORP-2). Every rule here is one the
 * lane would otherwise have to guess, so each is pinned by a negative case and the whole thing is
 * guarded by a positive one - a suite where everything throws would pass this file while proving
 * nothing.
 */
class CorpusManifestTest {

    private static final String CURATED_SHA = "a".repeat(64);
    private static final String NON_CURATED_SHA = "b".repeat(64);

    @TempDir
    Path temp;

    @Test
    void readsAValidManifestAndItsSkipList() throws IOException {
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                row("BIOMD1", "biomodels/BIOMD1.xml", CURATED_SHA, "curated"),
                row("MODEL1", "biomodels/MODEL1.xml", NON_CURATED_SHA, "non-curated"));
        var skip = write("skip.tsv", CorpusManifest.skipHeader(),
                String.join("\t", NON_CURATED_SHA, "SBML 2.4 non-curated", "model has no units", "2026-11-18"));

        var loaded = CorpusManifest.load(manifest, skip);

        assertEquals(2, loaded.rows().size());
        assertEquals(1, loaded.skips().size());
        assertEquals(CorpusManifest.Curation.CURATED, loaded.bySha256(CURATED_SHA).curation());
        assertEquals("biomodels/BIOMD1.xml", loaded.bySha256(CURATED_SHA).file());
    }

    @Test
    void rejectsAHeaderThatIsNotTheContract() throws IOException {
        var manifest = write("manifest.tsv", "source\tcuration", row("BIOMD1", "x.xml", CURATED_SHA, "curated"));

        var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()));

        assertTrue(failure.getMessage().contains("header"), failure.getMessage());
    }

    @Test
    void rejectsARowWhoseFileEscapesTheCache() throws IOException {
        for (var escape : new String[] {"../etc/passwd", "/etc/passwd", "files/../../x.xml"}) {
            var manifest = write("manifest.tsv", CorpusManifest.header(),
                    row("BIOMD1", escape, CURATED_SHA, "curated"));

            var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()),
                    escape);

            assertTrue(failure.getMessage().contains("escapes"), failure.getMessage());
        }
    }

    @Test
    void rejectsADuplicateFile() throws IOException {
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                row("BIOMD1", "biomodels/same.xml", CURATED_SHA, "curated"),
                row("BIOMD2", "biomodels/same.xml", NON_CURATED_SHA, "non-curated"));

        var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()));

        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    @Test
    void rejectsTheSameSha256ClaimedByTwoDifferentFiles() throws IOException {
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                row("BIOMD1", "biomodels/BIOMD1.xml", CURATED_SHA, "curated"),
                row("BIOMD2", "biomodels/BIOMD2.xml", CURATED_SHA, "non-curated"));

        var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()));

        assertTrue(failure.getMessage().contains("sha256"), failure.getMessage());
        assertTrue(failure.getMessage().contains("biomodels/BIOMD1.xml"), failure.getMessage());
        assertTrue(failure.getMessage().contains("biomodels/BIOMD2.xml"), failure.getMessage());
    }

    @Test
    void rejectsAShaThatIsNot64LowercaseHex() throws IOException {
        for (var bad : new String[] {"abc", "A".repeat(64), "g".repeat(64), "a".repeat(63)}) {
            var manifest = write("manifest.tsv", CorpusManifest.header(),
                    row("BIOMD1", "biomodels/BIOMD1.xml", bad, "curated"));

            var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()),
                    bad);

            assertTrue(failure.getMessage().contains("sha256"), failure.getMessage());
        }
    }

    @Test
    void rejectsAnEmptyLevelOrVersion() throws IOException {
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                String.join("\t", "biomodels", "curated", "SBML", "", "4", "BIOMD1", "main",
                        "biomodels/BIOMD1.xml", "1024", CURATED_SHA, "https://example.org/BIOMD1", "2026-11-18"));

        var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()));

        assertTrue(failure.getMessage().contains("level"), failure.getMessage());
    }

    @Test
    void readsALegalMainAndSidecarPairInEitherOrder() throws IOException {
        // The sidecar comes first on purpose: the pair is checked once the whole file is read (CORP-10).
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                sidecar("BIOMD1", "biomodels/BIOMD1.xml@@cmeta", NON_CURATED_SHA, "curated"),
                row("BIOMD1", "biomodels/BIOMD1.xml", CURATED_SHA, "curated"));

        var loaded = CorpusManifest.load(manifest, absentSkip());

        assertEquals(2, loaded.rows().size());
        assertEquals(CorpusManifest.Role.SIDECAR, loaded.bySha256(NON_CURATED_SHA).role());
        assertEquals("biomodels/BIOMD1.xml@@cmeta", loaded.bySha256(NON_CURATED_SHA).file());
        assertEquals("BIOMD1", loaded.bySha256(NON_CURATED_SHA).id());
        assertEquals(CorpusManifest.Role.MAIN, loaded.bySha256(CURATED_SHA).role());
    }

    @Test
    void rejectsASidecarWhoseModelIsAbsent() throws IOException {
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                row("MODEL1", "biomodels/MODEL1.xml", CURATED_SHA, "curated"),
                sidecar("BIOMD1", "biomodels/BIOMD1.xml@@cmeta", NON_CURATED_SHA, "curated"));

        var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()));

        assertTrue(failure.getMessage().startsWith("manifest.tsv:3: "), failure.getMessage());
        assertTrue(failure.getMessage().contains("biomodels/BIOMD1.xml@@cmeta"), failure.getMessage());
        assertTrue(failure.getMessage().contains("not the '@@cmeta' sibling of a main row"), failure.getMessage());
    }

    @Test
    void rejectsASidecarWhoseModelExistsUnderADifferentId() throws IOException {
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                row("MODEL1", "biomodels/BIOMD1.xml", CURATED_SHA, "curated"),
                sidecar("BIOMD1", "biomodels/BIOMD1.xml@@cmeta", NON_CURATED_SHA, "curated"));

        var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()));

        assertTrue(failure.getMessage().startsWith("manifest.tsv:3: "), failure.getMessage());
        assertTrue(failure.getMessage().contains("'BIOMD1' of biomodels/curated"), failure.getMessage());
        assertTrue(failure.getMessage().contains("not the '@@cmeta' sibling of a main row"), failure.getMessage());
    }

    @Test
    void rejectsASidecarWhoseFileNameIsNotTheParentPlusTheSuffix() throws IOException {
        for (var notTheSibling : new String[] {"biomodels/BIOMD1-cmeta", "biomodels/BIOMD1.xml.cmeta",
            "biomodels/other.xml@@cmeta"}) {
            var manifest = write("manifest.tsv", CorpusManifest.header(),
                    row("BIOMD1", "biomodels/BIOMD1.xml", CURATED_SHA, "curated"),
                    sidecar("BIOMD1", notTheSibling, NON_CURATED_SHA, "curated"));

            var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, absentSkip()),
                    notTheSibling);

            assertTrue(failure.getMessage().startsWith("manifest.tsv:3: "), failure.getMessage());
            assertTrue(failure.getMessage().contains("not the '@@cmeta' sibling of a main row"), failure.getMessage());
        }
    }

    @Test
    void rejectsACuratedRowThatIsSkipped() throws IOException {
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                row("BIOMD1", "biomodels/BIOMD1.xml", CURATED_SHA, "curated"));
        var skip = write("skip.tsv", CorpusManifest.skipHeader(),
                String.join("\t", CURATED_SHA, "SBML 2.4 curated", "would whitewash the gate", "2026-11-18"));

        var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, skip));

        assertTrue(failure.getMessage().contains("curated"), failure.getMessage());
    }

    @Test
    void rejectsASkipForAShaThatIsNotInTheManifest() throws IOException {
        var manifest = write("manifest.tsv", CorpusManifest.header(),
                row("MODEL1", "biomodels/MODEL1.xml", NON_CURATED_SHA, "non-curated"));
        var skip = write("skip.tsv", CorpusManifest.skipHeader(),
                String.join("\t", CURATED_SHA, "any", "no such row", "2026-11-18"));

        var failure = assertThrows(IllegalArgumentException.class, () -> CorpusManifest.load(manifest, skip));

        assertTrue(failure.getMessage().contains("skip"), failure.getMessage());
    }

    @Test
    void readsTheCommittedManifestOnceItHasBeenHarvested() throws IOException {
        var manifest = Path.of("..", "..", "corpus", "manifest.tsv");
        var skip = Path.of("..", "..", "corpus", "skip.tsv");
        assumeTrue(Files.exists(manifest) && Files.exists(skip), "no corpus cache harvested yet (CORP-3)");

        var loaded = CorpusManifest.load(manifest, skip);

        assertFalse(loaded.rows().isEmpty(), "a harvested manifest has rows");
        assertTrue(loaded.rows().stream().anyMatch(row -> row.curation() == CorpusManifest.Curation.CURATED),
                "the draw always carries the curated route");
    }

    private String row(String id, String file, String sha256, String curation) {
        return String.join("\t", "biomodels", curation, "SBML", "2", "4", id, "main", file, "1024", sha256,
                "https://example.org/" + id, "2026-11-18");
    }

    /** A PMR CellML {@code @@cmeta} sidecar row: same source, curation and id as its model. */
    private String sidecar(String id, String file, String sha256, String curation) {
        return String.join("\t", "biomodels", curation, "CellML", "-", "2.0", id, "sidecar", file, "64", sha256,
                "https://example.org/" + id + "@@cmeta", "2026-11-18");
    }

    private Path write(String name, String header, String... rows) throws IOException {
        var lines = new java.util.ArrayList<String>();
        lines.add(header);
        lines.addAll(java.util.List.of(rows));
        var path = temp.resolve(name);
        Files.write(path, lines, StandardCharsets.UTF_8);
        return path;
    }

    private Path absentSkip() {
        return temp.resolve("no-skip.tsv");
    }
}

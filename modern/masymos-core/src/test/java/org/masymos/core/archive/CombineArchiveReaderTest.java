package org.masymos.core.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.masymos.core.archive.CombineArchiveFormat.ArchiveEntryKind;

class CombineArchiveReaderTest {
    private static final String SBML_FORMAT =
            "http://identifiers.org/combine.specifications/sbml.level-2.version-4";
    private static final String SEDML_FORMAT =
            "http://identifiers.org/combine.specifications/sed-ml.level-1.version-3";

    private static final String REAL_MANIFEST = """
            <?xml version='1.0' encoding='utf-8'?>
            <omexManifest xmlns="http://identifiers.org/combine.specifications/omex-manifest">
              <content location="." format="http://identifiers.org/combine.specifications/omex" />
              <content location="models/becker1.sbml" format="%s" />
              <content location="sedml/becker2010_fig2b-user.sedml" format="%s" master="true" />
              <content location="metadata.rdf" format="http://identifiers.org/combine.specifications/omex-metadata" />
            </omexManifest>
            """.formatted(SBML_FORMAT, SEDML_FORMAT);

    @Test
    void readsRealShapedArchiveInManifestOrderWithContent() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("models/becker1.sbml", "<sbml/>");
        members.put("sedml/becker2010_fig2b-user.sedml", "<sedML/>");
        members.put("metadata.rdf", "<rdf:RDF/>");
        members.put("manifest.xml", REAL_MANIFEST);
        // Not listed in the manifest: must be ignored, never exposed.
        members.put("stray.bin", "ignored");

        var archive = new CombineArchiveReader().read(streamOf(zip(members)));

        var entries = archive.entries();
        assertEquals(3, entries.size());
        assertEquals("models/becker1.sbml", entries.get(0).location());
        assertEquals("sedml/becker2010_fig2b-user.sedml", entries.get(1).location());
        assertEquals("metadata.rdf", entries.get(2).location());

        var sbml = entries.get(0);
        assertFalse(sbml.master());
        assertEquals(ArchiveEntryKind.SBML, sbml.format().kind());
        assertEquals(2, sbml.format().level());
        assertEquals(4, sbml.format().version());
        assertEquals(SBML_FORMAT, sbml.format().formatUri());
        assertArrayEquals("<sbml/>".getBytes(StandardCharsets.UTF_8),
                archive.content("models/becker1.sbml").orElseThrow());

        var sedml = entries.get(1);
        assertTrue(sedml.master());
        assertEquals(ArchiveEntryKind.SEDML, sedml.format().kind());
        assertEquals(1, sedml.format().level());
        assertEquals(3, sedml.format().version());

        var metadata = entries.get(2);
        assertFalse(metadata.master());
        assertEquals(ArchiveEntryKind.METADATA, metadata.format().kind());
        assertNull(metadata.format().level());
        assertNull(metadata.format().version());

        assertTrue(archive.content("metadata.rdf").isPresent());
        assertTrue(archive.content("stray.bin").isEmpty());
        assertTrue(archive.content("models/missing.sbml").isEmpty());
        assertEquals("sedml/becker2010_fig2b-user.sedml", archive.masterEntry().orElseThrow().location());
    }

    /**
     * {@code REL-002}: the whole archive is held once. The array the reader inflated is the array the
     * archive hands back, so a second read is a lookup and not a second copy of the member, and the
     * entries stay in the manifest's order.
     */
    @Test
    void contentIsTheOneArrayTheReaderBuffered() throws IOException {
        var manifest = """
                <?xml version='1.0' encoding='utf-8'?>
                <omexManifest xmlns="http://identifiers.org/combine.specifications/omex-manifest">
                  <content location="models/becker1.sbml" format="%s" />
                  <content location="metadata.rdf" format="http://identifiers.org/combine.specifications/omex-metadata" />
                </omexManifest>
                """.formatted(SBML_FORMAT);
        var members = new LinkedHashMap<String, String>();
        // The zip lists the members in another order than the manifest, so the assertion below is
        // about the manifest and not about how the packer happened to write the archive.
        members.put("metadata.rdf", "<rdf:RDF/>");
        members.put("models/becker1.sbml", "<sbml level=\"2\" version=\"4\"/>");
        members.put("manifest.xml", manifest);

        var archive = new CombineArchiveReader().read(streamOf(zip(members)));

        var first = archive.content("models/becker1.sbml").orElseThrow();
        var second = archive.content("models/becker1.sbml").orElseThrow();
        assertSame(first, second, "a second read hands out the stored array, not a copy of it");
        assertArrayEquals("<sbml level=\"2\" version=\"4\"/>".getBytes(StandardCharsets.UTF_8), first);
        assertEquals(List.of("models/becker1.sbml", "metadata.rdf"),
                archive.entries().stream().map(CombineArchiveEntry::location).toList(),
                "the entries keep the manifest's order");
    }

    @Test
    void rejectsArchiveWithoutRootManifest() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("models/becker1.sbml", "<sbml/>");
        members.put("omex/manifest.xml", REAL_MANIFEST);

        var bytes = zip(members);
        var exception = assertThrows(CombineArchiveException.class,
                () -> new CombineArchiveReader().read(streamOf(bytes)));
        assertTrue(exception.getMessage().contains("manifest.xml"), exception.getMessage());
    }

    @Test
    void rejectsManifestLocationsThatEscapeTheArchive() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("manifest.xml", manifestWith("../evil.txt", SBML_FORMAT));

        var bytes = zip(members);
        assertThrows(CombineArchiveException.class, () -> new CombineArchiveReader().read(streamOf(bytes)));
    }

    @Test
    void decodesFormatsWithoutLevelOrVersionAsNulls() {
        var omex = CombineArchiveFormat.parse("http://identifiers.org/combine.specifications/omex");
        assertEquals(ArchiveEntryKind.OMEX, omex.kind());
        assertNull(omex.level());
        assertNull(omex.version());

        var cellml = CombineArchiveFormat.parse("http://identifiers.org/combine.specifications/cellml");
        assertEquals(ArchiveEntryKind.CELLML, cellml.kind());
        assertNull(cellml.level());
        assertNull(cellml.version());

        var unknown = CombineArchiveFormat.parse("");
        assertEquals(ArchiveEntryKind.OTHER, unknown.kind());
        assertNull(unknown.level());
        assertNull(unknown.version());

        var unnamedLevel = CombineArchiveFormat.parse(SBML_FORMAT);
        assertEquals(ArchiveEntryKind.SBML, unnamedLevel.kind());
        assertEquals(2, unnamedLevel.level());
        assertEquals(4, unnamedLevel.version());
    }

    @Test
    void readsAManifestWrappedInAPlainManifestElement() throws IOException {
        // Measured on BioModels BIOMD0000001081 (2026-11-19, CORP-4): the omexManifest sits inside a
        // plain <manifest> wrapper and the namespace is the https spelling of the same URI.
        var manifest = """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest>
                   <omexManifest xmlns="https://identifiers.org/combine.specifications/omex-manifest">
                      <content format="https://identifiers.org/combine.specifications:omex" location="."/>
                      <content format="https://identifiers.org/combine.specifications:sbml"
                               location="./model.xml"
                               master="True"/>
                   </omexManifest>
                </manifest>
                """;

        var archive = new CombineArchiveReader()
                .read(streamOf(zip(Map.of("manifest.xml", manifest, "model.xml", "<sbml/>"))));

        assertEquals(1, archive.entries().size());
        // The declared location carries a leading ./ while the zip member does not; the reader must
        // bind the content anyway, which is what makes the archive a document rather than a listing.
        assertEquals("model.xml", archive.entries().getFirst().location());
        assertArrayEquals("<sbml/>".getBytes(StandardCharsets.UTF_8),
                archive.content("model.xml").orElseThrow(),
                "the entry content must be bound by its normalised location");
        assertEquals(ArchiveEntryKind.SBML, archive.entries().getFirst().format().kind());
        assertTrue(archive.masterEntry().isPresent(), "the master flag survives the wrapper");
    }

    @Test
    void bindsAMemberTheManifestNamesByAPathTheArchiveDoesNotContain() throws IOException {
        // Measured on the three FROG archives on BioModels (2026-11-20, CORP-8): the manifest declares
        // the member under the packer's home directory while the zip holds it at the root, so the
        // archive read as "no parsable member" and a curated row could not ingest.
        var manifest = manifestWith(".//home/kramanlab/Tanisha/model.xml", SBML_FORMAT);

        var archive = new CombineArchiveReader()
                .read(streamOf(zip(Map.of("manifest.xml", manifest, "model.xml", "<sbml/>"))));

        assertEquals("model.xml", archive.entries().getFirst().location(),
                "the entry is re-pointed at the member that actually answers to its file name");
        assertArrayEquals("<sbml/>".getBytes(StandardCharsets.UTF_8),
                archive.content("model.xml").orElseThrow());
    }

    @Test
    void refusesToGuessWhenTwoMembersShareTheDeclaredFileName() throws IOException {
        // Binding by base name is only honest when it is unambiguous: two members named model.xml name
        // two different documents, and picking one would silently index the wrong model.
        var manifest = manifestWith(".//home/kramanlab/model.xml", SBML_FORMAT);

        var archive = new CombineArchiveReader()
                .read(streamOf(zip(Map.of(
                        "manifest.xml", manifest,
                        "a/model.xml", "<sbml a='1'/>",
                        "b/model.xml", "<sbml b='2'/>"))));

        assertTrue(archive.content("a/model.xml").isEmpty());
        assertTrue(archive.content("b/model.xml").isEmpty());
    }

    /**
     * {@code REL-001}: the read is bounded in entry count, not only in bytes. The entry is refused
     * <em>before</em> it is buffered, and the message names it, so an operator knows which member
     * crossed the cap.
     */
    @Test
    void rejectsTheEntryAfterTheCapIsReached() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("manifest.xml", manifestWith("a.sbml", SBML_FORMAT));
        members.put("a.sbml", "<sbml/>");
        members.put("b.sbml", "<sbml/>");
        members.put("c.sbml", "<sbml/>");

        var reader = new CombineArchiveReader(3, 3);
        var exception = assertThrows(CombineArchiveException.class, () -> reader.read(streamOf(zip(members))));

        assertTrue(exception.getMessage().contains("entry 4"), exception.getMessage());
        assertTrue(exception.getMessage().contains("c.sbml"), exception.getMessage());
        assertFalse(new CombineArchiveReader(4, 3).read(streamOf(zip(members))).entries().isEmpty(),
                "exactly at the cap the same archive still reads");
    }

    /**
     * The manifest half of the same bound: {@code <content>} elements are counted before any one of
     * them is bound, because the bind scan is quadratic in the two counts together.
     */
    @Test
    void countsContentElementsBeforeBinding() throws IOException {
        var extra = "  <content location=\"b.sbml\" format=\"" + SBML_FORMAT + "\" />\n"
                + "  <content location=\"c.sbml\" format=\"" + SBML_FORMAT + "\" />\n"
                + "  <content location=\"d.sbml\" format=\"" + SBML_FORMAT + "\" />\n";
        var members = new LinkedHashMap<String, String>();
        members.put("manifest.xml", manifestWith("a.sbml", SBML_FORMAT)
                .replace("</omexManifest>", extra + "</omexManifest>"));
        members.put("a.sbml", "<sbml/>");

        var exception = assertThrows(CombineArchiveException.class,
                () -> new CombineArchiveReader(10, 3).read(streamOf(zip(members))));
        assertTrue(exception.getMessage().contains("element 4"), exception.getMessage());

        assertEquals(4, new CombineArchiveReader(10, 4).read(streamOf(zip(members))).entries().size(),
                "four elements at the cap of four still read");
    }

    /**
     * The byte half of the same bound, and the path {@code REL-002} rewrote: the member is inflated
     * into the array it is handed on, so the cap must still trip on an oversized entry instead of
     * letting that array grow past it.
     */
    @Test
    void rejectsTheEntryThatExceedsTheEntryByteCap() throws IOException {
        var oversized = new byte[(int) CombineArchiveReader.MAX_ENTRY_BYTES + 1];
        var bytes = zipBytes(Map.of(
                "manifest.xml", manifestWith("big.bin", SBML_FORMAT).getBytes(StandardCharsets.UTF_8),
                "big.bin", oversized));

        var exception = assertThrows(CombineArchiveException.class,
                () -> new CombineArchiveReader().read(streamOf(bytes)));

        assertTrue(exception.getMessage().contains("archive entry exceeds"), exception.getMessage());
        assertTrue(exception.getMessage().contains("big.bin"), exception.getMessage());
    }

    private static String manifestWith(String location, String format) {
        return """
                <?xml version='1.0' encoding='utf-8'?>
                <omexManifest xmlns="http://identifiers.org/combine.specifications/omex-manifest">
                  <content location="%s" format="%s" />
                </omexManifest>
                """.formatted(location, format);
    }

    private static ByteArrayInputStream streamOf(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] zip(Map<String, String> members) throws IOException {
        var bytes = new LinkedHashMap<String, byte[]>();
        members.forEach((name, body) -> bytes.put(name, body.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(bytes);
    }

    private static byte[] zipBytes(Map<String, byte[]> members) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var member : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}

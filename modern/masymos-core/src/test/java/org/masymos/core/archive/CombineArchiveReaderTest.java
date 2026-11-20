package org.masymos.core.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var member : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}

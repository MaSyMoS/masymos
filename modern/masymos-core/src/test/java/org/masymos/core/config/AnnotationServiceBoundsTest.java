package org.masymos.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.masymos.core.annotation.HttpAnnotationEgressPolicy;
import org.masymos.core.archive.CombineArchiveException;
import org.masymos.core.archive.CombineArchiveReader;

class AnnotationServiceBoundsTest {

    private static final String SBML_FORMAT = "http://identifiers.org/combine.specifications/sbml";

    @Test
    void theArchiveCapsReachCombineArchiveReaderThroughTheRecord() throws IOException {
        var bounds = new AnnotationServiceBounds(HttpAnnotationEgressPolicy.defaults(), 2, 1_000);
        var members = new LinkedHashMap<String, String>();
        members.put("manifest.xml", """
                <?xml version='1.0' encoding='utf-8'?>
                <omexManifest xmlns="http://identifiers.org/combine.specifications/omex-manifest">
                  <content location="%s" format="%s" />
                  <content location="%s" format="%s" />
                </omexManifest>
                """.formatted("a.sbml", SBML_FORMAT, "b.sbml", SBML_FORMAT));
        members.put("a.sbml", "<sbml/>");
        members.put("b.sbml", "<sbml/>");

        // The cap counts the manifest too: two members fit, the third (b.sbml) crosses it.
        var reader = new CombineArchiveReader(bounds);
        var exception = assertThrows(CombineArchiveException.class,
                () -> reader.read(new ByteArrayInputStream(zip(members))));

        assertTrue(exception.getMessage().contains("entry 3"), exception.getMessage());
        assertTrue(exception.getMessage().contains("b.sbml"), exception.getMessage());

        var raised = new AnnotationServiceBounds(HttpAnnotationEgressPolicy.defaults(), 3, 1_000);
        assertEquals(2, new CombineArchiveReader(raised).read(new ByteArrayInputStream(zip(members))).entries().size(),
                "at a cap of three the same archive reads, which proves the cap came from the record");
    }

    @Test
    void defaultsCarryTheMeasuredCapsAndTheDefaultEgressPolicy() {
        var bounds = AnnotationServiceBounds.defaults();

        assertEquals(1_000, bounds.archiveEntries());
        assertEquals(1_000, bounds.manifestContentElements());
        assertEquals(HttpAnnotationEgressPolicy.defaults(), bounds.egress());
        assertEquals(AnnotationServiceBounds.DEFAULT_ARCHIVE_ENTRIES, bounds.archiveEntries());
        assertEquals(AnnotationServiceBounds.DEFAULT_MANIFEST_CONTENT_ELEMENTS, bounds.manifestContentElements());
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

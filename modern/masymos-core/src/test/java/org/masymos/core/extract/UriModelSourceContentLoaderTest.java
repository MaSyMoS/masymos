package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.masymos.core.archive.CombineArchiveUri;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class UriModelSourceContentLoaderTest {

    private static final String MANIFEST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <omexManifest xmlns="http://identifiers.org/combine.specifications/omex-manifest">
              <content location="models/a.sbml" format="http://identifiers.org/combine.specifications/sbml.level-3.version-1"/>
            </omexManifest>
            """;

    private final UriModelSourceContentLoader loader = new UriModelSourceContentLoader();

    @Test
    void readsEntryFromArchiveWithoutExtracting(@TempDir Path tempDir) throws IOException {
        var archive = writeArchive(tempDir, Map.of(
                "manifest.xml", MANIFEST,
                "models/a.sbml", "<sbml level=\"3\" version=\"1\"/>"
        ));

        var entryUri = CombineArchiveUri.of(archive.toUri(), "models/a.sbml");
        var content = new String(open(entryUri), StandardCharsets.UTF_8);

        assertEquals("<sbml level=\"3\" version=\"1\"/>", content);
    }

    @Test
    void failsForMissingArchiveEntry(@TempDir Path tempDir) throws IOException {
        var archive = writeArchive(tempDir, Map.of("manifest.xml", MANIFEST));

        var entryUri = CombineArchiveUri.of(archive.toUri(), "models/absent.sbml");

        assertThrows(IOException.class, () -> open(entryUri));
    }

    @Test
    void stillReadsPlainFiles(@TempDir Path tempDir) throws IOException {
        var file = tempDir.resolve("model.sbml");
        Files.writeString(file, "<sbml/>");

        assertEquals("<sbml/>", new String(open(file.toUri()), StandardCharsets.UTF_8));
    }

    private byte[] open(java.net.URI uri) throws IOException {
        var source = new ModelSource(new ModelReference("model.xml", uri, ModelType.SBML));
        try (var stream = loader.open(source)) {
            return stream.readAllBytes();
        }
    }

    private static Path writeArchive(Path directory, Map<String, String> members) throws IOException {
        var archive = directory.resolve("archive.omex");
        var ordered = new LinkedHashMap<>(members);
        try (OutputStream out = Files.newOutputStream(archive); var zip = new ZipOutputStream(out)) {
            for (var member : ordered.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }
}

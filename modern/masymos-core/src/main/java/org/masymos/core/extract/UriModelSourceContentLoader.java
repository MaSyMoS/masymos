package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.masymos.core.archive.CombineArchiveReader;
import org.masymos.core.archive.CombineArchiveUri;

public final class UriModelSourceContentLoader implements ModelSourceContentLoader {
    private final CombineArchiveReader archiveReader = new CombineArchiveReader();

    @Override
    public InputStream open(ModelSource source) throws IOException {
        var uri = source.reference().sourceUri();
        var location = CombineArchiveUri.parse(uri);
        if (location.isPresent()) {
            return openArchiveEntry(uri, location.get());
        }
        return openUri(uri);
    }

    /**
     * Reads one entry of a COMBINE archive identified by {@code <archive-uri>!/<entry-path>}. This is
     * what lets an archive be indexed without extracting it to a temporary directory, so entry URIs
     * stay valid after the run.
     */
    private InputStream openArchiveEntry(URI entryUri, CombineArchiveUri.Location location) throws IOException {
        try (var archiveStream = openUri(location.archiveUri())) {
            var archive = archiveReader.read(archiveStream);
            var content = archive.content(location.entryPath());
            if (content.isEmpty()) {
                // Manifest locations are stored verbatim; a URI-encoded path has to be decoded first.
                var decoded = URLDecoder.decode(location.entryPath(), StandardCharsets.UTF_8);
                content = archive.content(decoded);
            }
            if (content.isEmpty()) {
                throw new IOException("COMBINE archive entry not found: " + entryUri);
            }
            return new ByteArrayInputStream(content.get());
        }
    }

    private InputStream openUri(URI uri) throws IOException {
        if (isFileUri(uri)) {
            return Files.newInputStream(Path.of(uri));
        }
        return uri.toURL().openStream();
    }

    private boolean isFileUri(URI uri) {
        return uri.getScheme() == null || uri.getScheme().equalsIgnoreCase("file");
    }
}

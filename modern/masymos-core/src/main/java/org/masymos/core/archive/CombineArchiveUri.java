package org.masymos.core.archive;

import java.net.URI;
import java.util.Optional;

/**
 * Stable identity for a file inside a COMBINE archive: {@code <archive-uri>!/<entry-path>}.
 *
 * <p>Extracting archive entries to a temporary directory and indexing those file URIs would put
 * paths into the graph that stop existing the moment the run ends, and SED-ML references inside the
 * archive would never match. The {@code !/} form keeps the archive as the durable identity, survives
 * the run, and behaves correctly with {@link java.net.URI#resolve} for relative references such as
 * {@code ../models/adlung1.sbml}.
 */
public final class CombineArchiveUri {

    public static final String SEPARATOR = "!/";

    private CombineArchiveUri() {
    }

    public static URI of(java.net.URI archiveUri, String entryPath) {
        if (archiveUri == null) {
            throw new CombineArchiveException("archive URI must not be null");
        }
        if (entryPath == null || entryPath.isBlank()) {
            throw new CombineArchiveException("archive entry path must not be blank");
        }
        var normalized = entryPath.startsWith("/") ? entryPath.substring(1) : entryPath;
        return java.net.URI.create(archiveUri + SEPARATOR + normalized);
    }

    public static boolean isArchiveUri(java.net.URI uri) {
        return uri != null && uri.toString().contains(SEPARATOR);
    }

    public static Optional<Location> parse(java.net.URI uri) {
        if (uri == null) {
            return Optional.empty();
        }
        var text = uri.toString();
        var index = text.indexOf(SEPARATOR);
        if (index <= 0 || index == text.length() - SEPARATOR.length()) {
            return Optional.empty();
        }
        return Optional.of(new Location(
                java.net.URI.create(text.substring(0, index)),
                text.substring(index + SEPARATOR.length())
        ));
    }

    /** An archive URI split into the archive itself and the entry path inside it. */
    public record Location(java.net.URI archiveUri, String entryPath) {
    }
}

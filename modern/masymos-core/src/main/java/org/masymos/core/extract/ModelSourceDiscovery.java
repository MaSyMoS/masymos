package org.masymos.core.extract;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.masymos.core.archive.CombineArchiveUri;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

public final class ModelSourceDiscovery {
    private static final List<String> MODEL_FILE_EXTENSIONS = List.of(".xml", ".sbml", ".cellml", ".sedml", ".owl");

    public List<ModelSource> fromListFiles(Path directory, ModelType modelType) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".list"))
                    .sorted()
                    .flatMap(path -> readListFile(path, modelType).stream())
                    .toList();
        }
    }

    public List<ModelSource> fromModelFiles(Path directory, ModelType modelType) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::hasKnownModelExtension)
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> new ModelSource(new ModelReference(
                            path.getFileName().toString(),
                            path.toUri(),
                            modelType
                    )))
                    .toList();
        }
    }

    public List<ModelSource> fromUris(List<URI> sourceUris, ModelType modelType) {
        return sourceUris.stream()
                .map(uri -> new ModelSource(new ModelReference(deriveFileId(uri, modelType), uri, modelType)))
                .toList();
    }

    public String deriveFileId(URI sourceUri, ModelType modelType) {
        if (CombineArchiveUri.isArchiveUri(sourceUri)) {
            // An archive entry is identified by the entry path alone. This is checked before the
            // query-style branch below because an entry path is an unambiguous identity, while that
            // branch keys a URI by whatever follows its last '='.
            return archiveEntryFileId(sourceUri);
        }
        var source = sourceUri.toString();
        if (modelType == ModelType.SBML && source.contains("=")) {
            var fileId = source.substring(source.lastIndexOf('=') + 1);
            if (!fileId.isBlank()) {
                return fileId;
            }
        }

        return lastPathSegment(sourceUri);
    }

    /**
     * The last path segment of a source URI, or the URI itself when it carries no usable path.
     *
     * <p>This is the one implementation of the rule. {@code C02D-4} recorded that the archive write
     * path kept a second copy of it, so a change to the segments an identity is built from could
     * land in one call site and not the other; {@code CombineArchiveCypher.fileId} calls this
     * instead of re-deriving the segment.</p>
     */
    public static String lastPathSegment(URI sourceUri) {
        var path = sourceUri.getPath();
        if (path == null || path.isBlank()) {
            return sourceUri.toString();
        }
        var normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        var slashIndex = normalized.lastIndexOf('/');
        var segment = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        return segment.isEmpty() ? sourceUri.toString() : segment;
    }

    /**
     * The {@code fileId} of one COMBINE archive entry: its file name when the entry sits at the archive
     * root, otherwise {@code fileName@directory}, both taken from the normalised entry path.
     *
     * <p>Without the directory, two entries {@code models/a/x.sbml} and {@code b/x.sbml} of one archive
     * would both be {@code x.sbml} and collide on the {@code fileId} guard. The archive's own path is
     * never part of the identity - the archive is its own {@code Document} node, and including the path
     * would make a copied archive a new set of models. The path is already the encoded form
     * {@link CombineArchiveUri#normaliseEntryPath} produces, so the result is ASCII and holds neither
     * {@code !/} nor a separator; {@code @} cannot occur in a bare file name, so an entry never
     * collapses with a standalone file.
     *
     * @throws IllegalArgumentException when {@code entryUri} is not an archive entry URI
     */
    public String archiveEntryFileId(URI entryUri) {
        var location = CombineArchiveUri.parse(entryUri)
                .orElseThrow(() -> new IllegalArgumentException("not a COMBINE archive entry URI: " + entryUri));
        var path = CombineArchiveUri.normaliseEntryPath(location.entryPath())
                .orElseThrow(() -> new IllegalArgumentException("archive entry path is unusable: " + entryUri));
        var slashIndex = path.lastIndexOf('/');
        return slashIndex < 0 ? path : path.substring(slashIndex + 1) + "@" + path.substring(0, slashIndex);
    }

    private List<ModelSource> readListFile(Path path, ModelType modelType) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8)
                    .stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(URI::create)
                    .map(uri -> new ModelSource(new ModelReference(deriveFileId(uri, modelType), uri, modelType)))
                    .toList();
        } catch (IOException exception) {
            throw new ModelSourceDiscoveryException("failed to read model list file: " + path, exception);
        }
    }

    private boolean hasKnownModelExtension(Path path) {
        var filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return MODEL_FILE_EXTENSIONS.stream().anyMatch(filename::endsWith);
    }
}

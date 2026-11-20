package org.masymos.core.extract;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
        var source = sourceUri.toString();
        if (modelType == ModelType.SBML && source.contains("=")) {
            var fileId = source.substring(source.lastIndexOf('=') + 1);
            if (!fileId.isBlank()) {
                return fileId;
            }
        }

        var path = sourceUri.getPath();
        if (path != null && !path.isBlank()) {
            var normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            var slashIndex = normalized.lastIndexOf('/');
            return slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        }

        return source;
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

package org.masymos.core.extract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

public final class RemotePmrCellmlExternalCmetaResolver implements CellmlExternalCmetaResolver {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Supplier<HttpClient> httpClientFactory;
    private final ObjectMapper objectMapper;

    public RemotePmrCellmlExternalCmetaResolver() {
        this(() -> HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), new ObjectMapper());
    }

    RemotePmrCellmlExternalCmetaResolver(HttpClient httpClient, ObjectMapper objectMapper) {
        this(() -> httpClient, objectMapper);
    }

    private RemotePmrCellmlExternalCmetaResolver(Supplier<HttpClient> httpClientFactory, ObjectMapper objectMapper) {
        this.httpClientFactory = httpClientFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CellmlExternalCmeta> resolve(ModelSource source) {
        var sourceUri = source.reference().sourceUri();
        if (isLocal(sourceUri)) {
            return resolveLocal(sourceUri);
        }
        if (!"http".equalsIgnoreCase(sourceUri.getScheme()) && !"https".equalsIgnoreCase(sourceUri.getScheme())) {
            return List.of();
        }

        try {
            var request = HttpRequest.newBuilder(cmetaUri(sourceUri))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/vnd.physiome.pmr2.json.0")
                    .header("Accept", "application/vnd.physiome.pmr2.json.0")
                    .GET()
                    .build();
            var httpClient = httpClientFactory.get();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            var value = objectMapper.readValue(response.body(), PmrCmetaResponse.class);
            return List.of(value.toExternalCmeta());
        } catch (IOException | InterruptedException | UncheckedIOException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private static URI cmetaUri(URI sourceUri) {
        return URI.create(sourceUri.toString() + "/@@cmeta");
    }

    /**
     * {@code file:} sources are readable locally (CML-3). The sibling file is named exactly as the
     * PMR endpoint would serve it, {@code <model>@@cmeta}, next to the model, so a downloaded model
     * and its cmeta document behave like a PMR-uploaded pair. HTTP keeps its request, headers and
     * timeouts unchanged; this branch never opens a connection.
     */
    private List<CellmlExternalCmeta> resolveLocal(URI sourceUri) {
        var cmetaPath = Path.of(sourceUri).resolveSibling(Path.of(sourceUri).getFileName() + "@@cmeta");
        if (!Files.isRegularFile(cmetaPath)) {
            return List.of();
        }
        try {
            var value = objectMapper.readValue(Files.readString(cmetaPath, StandardCharsets.UTF_8), PmrCmetaResponse.class);
            return List.of(value.toExternalCmeta());
        } catch (IOException | RuntimeException exception) {
            // Same contract as the HTTP path: an unreadable source is no metadata, not a failed model.
            return List.of();
        }
    }

    private static boolean isLocal(URI sourceUri) {
        var scheme = sourceUri.getScheme();
        return scheme == null || "file".equalsIgnoreCase(scheme);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PmrCmetaResponse(
            @JsonProperty("model_author_org") String modelAuthorOrg,
            @JsonProperty("keywords") List<List<String>> keywords,
            @JsonProperty("citation_title") String citationTitle,
            @JsonProperty("citation_authors") List<List<String>> citationAuthors,
            @JsonProperty("title") String title,
            @JsonProperty("citation_journal") String citationJournal,
            @JsonProperty("model_author") String modelAuthor,
            @JsonProperty("citation_id") String citationId
    ) {
        CellmlExternalCmeta toExternalCmeta() {
            return new CellmlExternalCmeta(
                    modelAuthorOrg,
                    keywords,
                    citationTitle,
                    citationAuthors,
                    title,
                    citationJournal,
                    modelAuthor,
                    citationId
            );
        }
    }
}

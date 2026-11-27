package org.masymos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tools.jackson.databind.ObjectMapper;

abstract class ServiceIndexModelCommand implements Callable<Integer> {
    /**
     * The ceiling {@code --dump-path} enforces: the audit's {@code max-dump-bytes} of 52,428,800 (50 MiB),
     * compared against the body's length in UTF-16 characters. That count is the only measure available
     * without allocating the encoded array - the allocation this ceiling exists to avoid - and every
     * character encodes to at least one byte, so a body above the ceiling in characters is certainly
     * above it in bytes. A shorter body of multi-byte characters is not caught by this check.
     */
    static final int MAX_DUMP_BYTES = 52_428_800;

    private static final List<String> DIRECTORY_EXTENSIONS = List.of(".xml", ".sbml", ".cellml", ".sedml", ".owl");

    private final HttpRequestSender requestSender;
    private final ObjectMapper objectMapper;

    @ParentCommand
    private IndexCommand indexCommand;

    @Spec
    private CommandSpec spec;

    @Option(names = "--source", description = "Source URI. Can be specified multiple times.")
    private List<URI> sources;

    @Option(
            names = "--directory",
            description = "Directory scanned recursively for model files (.xml, .sbml, .cellml, .sedml, .owl)."
    )
    private Path directory;

    @Option(names = "--dump-path", description = "Write the response body to this file.")
    private Path dumpPath;

    @Option(names = "--quiet", description = "Do not print the response body.")
    private boolean quiet;

    ServiceIndexModelCommand() {
        this.requestSender = new JavaHttpRequestSender(() -> ServiceHttp.client(spec));
        this.objectMapper = new ObjectMapper();
    }

    ServiceIndexModelCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        this(new JavaHttpRequestSender(() -> httpClient), objectMapper);
    }

    ServiceIndexModelCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        this.requestSender = requestSender;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var requestFields = new LinkedHashMap<String, Object>();
        requestFields.put("sources", sourceUris());
        requestFields.putAll(additionalRequestFields());
        var requestBody = objectMapper.writeValueAsString(requestFields);
        var request = ServiceHttp.requestBuilder(spec)
                .timeout(ServiceHttp.requestTimeout(spec))
                .uri(URI.create(apiUrl() + endpointPath()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        var response = requestSender.send(request);
        if (dumpPath != null) {
            var body = response.body();
            if (body != null && body.length() > MAX_DUMP_BYTES) {
                error().println("response body of " + body.length() + " characters exceeds the --dump-path limit of "
                        + MAX_DUMP_BYTES + "; nothing was written");
                return 1;
            }
            dump(body);
        }
        if (!quiet) {
            output().println(response.body());
        }
        return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1;
    }

    abstract String endpointPath();

    Map<String, Object> additionalRequestFields() {
        return Map.of();
    }

    private List<URI> sourceUris() throws IOException {
        if (sources == null && directory == null) {
            throw new ParameterException(commandLine(), "--source or --directory is required");
        }
        var uris = new ArrayList<URI>();
        if (sources != null) {
            uris.addAll(sources);
        }
        if (directory != null) {
            uris.addAll(directoryUris());
        }
        return uris;
    }

    private List<URI> directoryUris() throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new ParameterException(commandLine(), "--directory is not a directory: " + directory);
        }
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> isModelFile(path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().toUri())
                    .sorted((left, right) -> left.getPath().compareTo(right.getPath()))
                    .toList();
        }
    }

    private boolean isModelFile(String name) {
        var lowerCaseName = name.toLowerCase(Locale.ROOT);
        return DIRECTORY_EXTENSIONS.stream().anyMatch(lowerCaseName::endsWith);
    }

    private void dump(String body) throws IOException {
        var target = dumpPath.toAbsolutePath();
        var parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, body == null ? "" : body, StandardCharsets.UTF_8);
    }

    private CommandLine commandLine() {
        return spec == null ? new CommandLine(this) : spec.commandLine();
    }

    private String apiUrl() {
        return indexCommand == null ? "http://localhost:8080" : indexCommand.apiUrl();
    }

    /** Where a failure line goes: the error stream, so a script's stdout stays the report it expects. */
    private PrintWriter error() {
        return spec == null ? new PrintWriter(System.err, true) : spec.commandLine().getErr();
    }

    private PrintWriter output() {
        return spec == null ? new PrintWriter(System.out, true) : spec.commandLine().getOut();
    }

    interface HttpClientFactory {
        HttpClient create();
    }

    interface HttpRequestSender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private record JavaHttpRequestSender(HttpClientFactory httpClientFactory) implements HttpRequestSender {
        @Override
        public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
            return httpClientFactory.create().send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}

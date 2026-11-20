package org.masymos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Command(
        name = "annotations-index",
        mixinStandardHelpOptions = true,
        description = "Rebuild the annotation keyword index through the MaSyMoS service."
)
public class MaintenanceAnnotationsCommand implements Callable<Integer> {
    /** The term-stage numeric counters, printed in this order, only when the response carries them. */
    static final List<String> TERM_COUNTERS = List.of(
            "termsRequested",
            "providerRequests",
            "cacheHits",
            "cacheMisses",
            "unresolvedTerms",
            "pageRequests",
            "cacheFailures");

    private final HttpRequestSender requestSender;
    private final ObjectMapper objectMapper;

    @ParentCommand
    private MaintenanceCommand maintenanceCommand;

    @Spec
    private CommandSpec spec;

    @Option(
            names = "--drop-existing",
            arity = "0..1",
            fallbackValue = "true",
            description = "Drop the existing annotation index before scanning (default: ${DEFAULT-VALUE})."
    )
    private boolean dropExistingIndex = false;

    @Option(names = "--limit", description = "Maximum number of resources to scan (default: ${DEFAULT-VALUE}).")
    private int limit = 500;

    @Option(
            names = "--retry-failed",
            arity = "0..1",
            fallbackValue = "true",
            description = "Retry resources that failed during a previous run (default: ${DEFAULT-VALUE})."
    )
    private boolean retryFailed = false;

    @Option(
            names = "--max-concurrency",
            description = "Maximum number of concurrent annotation fetches (default: ${DEFAULT-VALUE})."
    )
    private int maxConcurrency = 4;

    @Option(
            names = "--resolver-mode",
            paramLabel = "<providers|direct|off>",
            description = "How far annotation term resolution may go: providers, direct or off"
                    + " (default: ${DEFAULT-VALUE})."
    )
    private String resolverMode = "direct";

    @Option(
            names = "--refresh-terms",
            arity = "0..1",
            fallbackValue = "true",
            description = "Refresh the cached term definitions of the resources this run touches"
                    + " (default: ${DEFAULT-VALUE})."
    )
    private boolean refreshTerms = false;

    public MaintenanceAnnotationsCommand() {
        this(new JavaHttpRequestSender(HttpClient::newHttpClient), new ObjectMapper());
    }

    MaintenanceAnnotationsCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        this(new JavaHttpRequestSender(() -> httpClient), objectMapper);
    }

    MaintenanceAnnotationsCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        this.requestSender = requestSender;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl() + "/api/v1/maintenance/annotations/index" + queryString()))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        var response = requestSender.send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            output().println("Annotation index maintenance failed with HTTP status " + response.statusCode());
            output().println(response.body());
            return 1;
        }

        print(result(parse(response.body())));
        return 0;
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Annotation index response is not valid JSON", exception);
        }
    }

    private AnnotationIndexSummary result(JsonNode body) {
        return new AnnotationIndexSummary(
                body.path("scannedResources").asLong(0L),
                body.path("indexedResources").asLong(0L),
                body.path("skippedResources").asLong(0L),
                body.path("failedResources").asLong(0L),
                termInfo(body));
    }

    /**
     * The resolver fields the response actually carries, in the order they are printed.
     *
     * <p>A service older than the term stage sends none of them, so the map stays empty and the CLI
     * prints exactly the four counter lines it always printed. Only presence is decided here; a
     * field that is present is printed even when its value is zero or an empty list.
     */
    private Map<String, String> termInfo(JsonNode body) {
        var info = new LinkedHashMap<String, String>();
        putText(info, body, "resolverMode");
        putNames(info, body, "providersAvailable");
        for (var name : TERM_COUNTERS) {
            var node = body.path(name);
            if (node.isNumber()) {
                info.put(name, Long.toString(node.asLong(0L)));
            }
        }
        putNames(info, body, "providersDemoted");
        return info;
    }

    private static void putText(Map<String, String> info, JsonNode body, String name) {
        var node = body.path(name);
        if (node.isTextual()) {
            info.put(name, node.asString(""));
        }
    }

    private static void putNames(Map<String, String> info, JsonNode body, String name) {
        var node = body.path(name);
        if (!node.isArray()) {
            return;
        }
        var joined = new StringBuilder();
        for (var element : node) {
            joined.append(joined.isEmpty() ? "" : ",").append(element.asString(""));
        }
        info.put(name, joined.toString());
    }

    private void print(AnnotationIndexSummary summary) {
        var out = output();
        out.println("scannedResources: " + summary.scannedResources());
        out.println("indexedResources: " + summary.indexedResources());
        out.println("skippedResources: " + summary.skippedResources());
        out.println("failedResources: " + summary.failedResources());

        summary.termInfo().forEach((name, value) -> out.println(name + ": " + value));
        out.flush();
    }

    private String queryString() {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("dropExistingIndex", dropExistingIndex);
        parameters.put("limit", limit);
        parameters.put("retryFailed", retryFailed);
        parameters.put("maxConcurrency", maxConcurrency);
        parameters.put("resolverMode", resolverMode);
        parameters.put("refreshTerms", refreshTerms);

        var query = new StringBuilder();
        for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
            query.append(query.isEmpty() ? '?' : '&');
            query.append(encode(parameter.getKey())).append('=').append(encode(parameter.getValue().toString()));
        }
        return query.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String apiUrl() {
        return maintenanceCommand == null ? "http://localhost:8080" : maintenanceCommand.apiUrl();
    }

    private PrintWriter output() {
        return spec == null ? new PrintWriter(System.out, true) : spec.commandLine().getOut();
    }

    /**
     * One run's counters: the four that always existed, then the term-stage fields of
     * {@link #termInfo} in the order they are printed. {@code termInfo} holds only what the response
     * carried, so a missing key means "the service does not speak about it", never "zero".
     */
    record AnnotationIndexSummary(
            long scannedResources,
            long indexedResources,
            long skippedResources,
            long failedResources,
            Map<String, String> termInfo) {

        AnnotationIndexSummary {
            termInfo = termInfo == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(termInfo));
        }
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

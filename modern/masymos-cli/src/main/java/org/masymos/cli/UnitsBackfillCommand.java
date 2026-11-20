package org.masymos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Command(
        name = "units-backfill",
        mixinStandardHelpOptions = true,
        description = "Backfill the unit layer of documents indexed before the unit layer existed."
)
public class UnitsBackfillCommand implements Callable<Integer> {
    /** The response counters, printed in this order, only when the response carries them as numbers. */
    static final List<String> COUNTERS = List.of(
            "documentsScanned",
            "documentsBackfilled",
            "documentsAlreadyComplete",
            "skippedWithoutStoredDocument",
            "skippedTruncatedDocument",
            "skippedUnsupportedType",
            "skippedUnparseable",
            "unitsCreated",
            "usesUnitCreated",
            "hasUnitCreated",
            "compositionCreated",
            "relationshipsSkipped",
            "writes");

    private final HttpRequestSender requestSender;
    private final ObjectMapper objectMapper;

    @ParentCommand
    private MaintenanceCommand maintenanceCommand;

    @Spec
    private CommandSpec spec;

    @Option(
            names = "--apply",
            arity = "0..1",
            fallbackValue = "true",
            description = "Write the backfilled unit layer instead of reporting a dry run"
                    + " (default: ${DEFAULT-VALUE})."
    )
    private boolean apply = false;

    public UnitsBackfillCommand() {
        this(new JavaHttpRequestSender(HttpClient::newHttpClient), new ObjectMapper());
    }

    UnitsBackfillCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        this(new JavaHttpRequestSender(() -> httpClient), objectMapper);
    }

    UnitsBackfillCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        this.requestSender = requestSender;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl() + "/api/v1/maintenance/units-backfill?apply=" + apply))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        var response = requestSender.send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            output().println("Unit backfill failed with HTTP status " + response.statusCode());
            output().println(response.body());
            return 1;
        }

        print(parse(response.body()));
        return 0;
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unit backfill response is not valid JSON", exception);
        }
    }

    private void print(JsonNode body) {
        var out = output();
        out.println("applied: " + body.path("applied").asBoolean(false));
        for (var name : COUNTERS) {
            var node = body.path(name);
            if (node.isNumber()) {
                out.println(name + ": " + node.asLong(0L));
            }
        }
        out.println("complete: " + (body.path("complete").asBoolean(false) ? "true" : "false"));
        out.flush();
    }

    private String apiUrl() {
        return maintenanceCommand == null ? "http://localhost:8080" : maintenanceCommand.apiUrl();
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

package org.masymos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import org.masymos.core.rederive.ReDeriveReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The re-derive sweep ({@code OPN-7}): which already indexed documents the current mapper would produce
 * differently, and - with {@code --apply} - the repair of each stale document's derived graph from its
 * stored source.
 */
@Command(
        name = "re-derive",
        mixinStandardHelpOptions = true,
        description = "Report which indexed documents the current mapper would produce differently;"
                + " --apply repairs them (read-only without the flag)."
)
public class ReDeriveCommand implements Callable<Integer> {
    /** The response counters, printed in this order, only when the response carries them as numbers. */
    static final List<String> COUNTERS = List.of(
            "documentsScanned",
            "documentsStale",
            "documentsCurrent",
            "documentsNotScanned",
            "skippedWithoutStoredDocument",
            "skippedTruncatedDocument",
            "skippedUnsupportedType",
            "skippedUnparseable",
            "missingDerivedKeys",
            "applied");

    private final HttpRequestSender requestSender;
    private final ObjectMapper objectMapper;

    @ParentCommand
    private MaintenanceCommand maintenanceCommand;

    @Spec
    private CommandSpec spec;

    @Option(
            names = "--limit",
            arity = "1",
            description = "Maximum documents this scan looks at; 0 means unbounded"
                    + " (default: ${DEFAULT-VALUE}). The rest are reported as documentsNotScanned."
    )
    private int limit = ReDeriveReport.DEFAULT_LIMIT;

    @Option(
            names = "--cursor",
            arity = "1",
            description = "Start after this position - the nextCursor a previous run printed. Omit to start"
                    + " at the beginning of the population."
    )
    private String cursor;

    @Option(
            names = "--apply",
            description = "Repair instead of only report: each stale document's derived graph is rewritten from"
                    + " its stored source, in one transaction per document, and no new version is written."
                    + " The applied counter in the report names how many were rewritten."
    )
    private boolean apply;

    public ReDeriveCommand() {
        this.requestSender = new JavaHttpRequestSender(() -> ServiceHttp.client(spec));
        this.objectMapper = new ObjectMapper();
    }

    ReDeriveCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        this(new JavaHttpRequestSender(() -> httpClient), objectMapper);
    }

    ReDeriveCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        this.requestSender = requestSender;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var request = ServiceHttp.requestBuilder(spec)
                .timeout(ServiceHttp.requestTimeout(spec))
                .uri(URI.create(apiUrl() + "/api/v1/maintenance/re-derive?limit=" + limit
                        + (cursor == null || cursor.isBlank()
                                ? ""
                                : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8))
                        + (apply ? "&apply=true" : "")))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        var response = requestSender.send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            error().println("Re-derive scan failed with HTTP status " + response.statusCode());
            error().println(response.body());
            return 1;
        }

        print(parse(response.body()));
        return 0;
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Re-derive scan response is not valid JSON", exception);
        }
    }

    private void print(JsonNode body) {
        var out = output();
        for (var name : COUNTERS) {
            var node = body.path(name);
            if (node.isNumber()) {
                out.println(name + ": " + node.asLong(0L));
            }
        }
        var missingByLabel = body.path("missingByLabel");
        if (missingByLabel.isObject()) {
            missingByLabel.propertyNames().forEach(label -> {
                var count = missingByLabel.path(label);
                if (count.isNumber() && count.asLong(0L) > 0) {
                    out.println("missing " + label + ": " + count.asLong(0L));
                }
            });
        }
        var nextCursor = body.path("nextCursor");
        if (nextCursor.isTextual() && !nextCursor.asText().isEmpty()) {
            out.println("nextCursor: " + nextCursor.asText());
        }
        // What the run did, not what the report carries: an --apply run that repaired nothing is still
        // an apply run, and a scan is still read-only.
        out.println("readOnly: " + !apply);
        out.flush();
    }

    private String apiUrl() {
        return maintenanceCommand == null ? "http://localhost:8080" : maintenanceCommand.apiUrl();
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

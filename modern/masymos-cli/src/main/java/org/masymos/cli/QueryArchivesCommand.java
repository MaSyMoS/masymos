package org.masymos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tools.jackson.databind.ObjectMapper;

/**
 * The COMBINE archive search ({@code POST /api/v1/queries/archives}). Each answer is one archive with
 * the number of documents it bundles, so an archive is findable by name or URI without listing its
 * entries - the surface {@code QRY-9} added.
 */
@Command(
        name = "archives",
        mixinStandardHelpOptions = true,
        description = "Query COMBINE archives through the MaSyMoS service."
)
public class QueryArchivesCommand implements Callable<Integer> {
    private final ObjectMapper objectMapper;
    private final HttpRequestSender requestSender;

    @ParentCommand
    private QueryCommand queryCommand;

    @Spec
    private CommandSpec spec;

    @Option(names = "--keyword", required = true, description = "Search keyword.")
    private String keyword;

    @Option(names = "--top", description = "Maximum number of results.")
    private int top = 10;

    @Option(
            names = "--cursor",
            description = "Continue after this position - the nextCursor a previous page printed. Omit to start"
                    + " at the beginning of the result set.")
    private String cursor;

    public QueryArchivesCommand() {
        this.requestSender = new JavaHttpRequestSender(() -> ServiceHttp.client(spec));
        this.objectMapper = new ObjectMapper();
    }

    QueryArchivesCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        this(new JavaHttpRequestSender(() -> httpClient), objectMapper);
    }

    QueryArchivesCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        this.requestSender = requestSender;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("keyword", keyword);
        fields.put("top", top);
        var requestBody = objectMapper.writeValueAsString(fields);
        var request = ServiceHttp.requestBuilder(spec)
                .timeout(ServiceHttp.requestTimeout(spec))
                .uri(URI.create(apiUrl() + "/api/v1/queries/archives" + cursorParameter()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        var response = requestSender.send(request);
        output().println(response.body());
        return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1;
    }

    /** The cursor as a request parameter, or nothing at all: a bare URL keeps the first page. */
    private String cursorParameter() {
        return cursor == null || cursor.isBlank()
                ? ""
                : "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
    }

    private String apiUrl() {
        return queryCommand == null ? "http://localhost:8080" : queryCommand.apiUrl();
    }

    private PrintWriter output() {
        return spec == null ? new PrintWriter(System.out, true) : spec.commandLine().getOut();
    }

    interface HttpRequestSender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private record JavaHttpRequestSender(QueryModelsCommand.HttpClientFactory httpClientFactory)
            implements HttpRequestSender {
        @Override
        public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
            return httpClientFactory.create().send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}

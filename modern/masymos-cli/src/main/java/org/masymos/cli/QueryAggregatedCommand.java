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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import tools.jackson.databind.ObjectMapper;

/**
 * The fused query ({@code POST /api/v1/queries/aggregated}): one ranked list of model files, produced
 * by a strategy over the selected entity rankers ({@code QRY-3}).
 *
 * <p>The strategy is required because the endpoint has no default - a fused answer without a named
 * strategy would be a result nobody could reproduce. Weights are optional and only reach
 * {@code bordaCombMnz}; the service ignores them for a strategy that produces its own order.
 */
@Command(
        name = "aggregated",
        mixinStandardHelpOptions = true,
        description = "Run a fused query over models, annotations, persons and publications."
)
public class QueryAggregatedCommand implements Callable<Integer> {
    private final ObjectMapper objectMapper;
    private final HttpRequestSender requestSender;

    @ParentCommand
    private QueryCommand queryCommand;

    @Spec
    private CommandSpec spec;

    @Option(names = "--keyword", required = true, description = "Search keyword.")
    private String keyword;

    @Option(
            names = "--strategy",
            required = true,
            description = "Fusion strategy id, e.g. bordaCombMnz or bestRank; GET /api/v1/queries/strategies lists them.")
    private String strategy;

    @Option(names = "--top", description = "Maximum number of model files.")
    private int top = 10;

    @Option(names = "--min-score", description = "Score floor for the annotation, person and publication rankers.")
    private Double minScore;

    @Option(
            names = "--types",
            split = ",",
            description = "Entity rankers to fuse: model, annotation, person, publication. Omitted fuses all four.")
    private List<String> types;

    @Option(
            names = "--model-types",
            split = ",",
            description = "Restrict the model ranker to these kinds: SBML, CELLML. Omitted means every kind.")
    private List<String> modelTypes;

    @Option(
            names = "--weights",
            split = ",",
            description = "Per-ranker weights as ranker=weight, e.g. model=4,annotation=3; bordaCombMnz only.")
    private List<String> weights;

    @Option(
            names = "--cursor",
            description = "Continue after this position - the nextCursor a previous page printed. Omit to start"
                    + " at the beginning of the result set.")
    private String cursor;

    public QueryAggregatedCommand() {
        this.requestSender = new JavaHttpRequestSender(() -> ServiceHttp.client(spec));
        this.objectMapper = new ObjectMapper();
    }

    QueryAggregatedCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        this(new JavaHttpRequestSender(() -> httpClient), objectMapper);
    }

    QueryAggregatedCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        this.requestSender = requestSender;
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        Map<String, Double> parsedWeights;
        try {
            parsedWeights = parseWeights(weights);
        } catch (IllegalArgumentException malformed) {
            return usage(malformed.getMessage());
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("keyword", keyword);
        fields.put("strategy", strategy);
        fields.put("top", top);
        if (minScore != null) {
            fields.put("minScore", minScore);
        }
        if (types != null && !types.isEmpty()) {
            fields.put("types", types);
        }
        if (modelTypes != null && !modelTypes.isEmpty()) {
            fields.put("modelTypes", modelTypes);
        }
        if (!parsedWeights.isEmpty()) {
            fields.put("weights", parsedWeights);
        }
        var requestBody = objectMapper.writeValueAsString(fields);
        var request = ServiceHttp.requestBuilder(spec)
                .timeout(ServiceHttp.requestTimeout(spec))
                .uri(URI.create(apiUrl() + "/api/v1/queries/aggregated" + cursorParameter()))
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

    /**
     * Parses {@code ranker=weight} entries. A malformed entry is the caller's error and must not reach
     * the service, which would answer a 400 that names a key the caller never wrote.
     *
     * @throws IllegalArgumentException if an entry has no {@code =} or a weight that is not a number
     */
    private static Map<String, Double> parseWeights(List<String> entries) {
        var parsed = new LinkedHashMap<String, Double>();
        if (entries == null) {
            return parsed;
        }
        for (var entry : entries) {
            var separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "--weights expects ranker=weight entries, e.g. model=4; got '" + entry + "'");
            }
            var ranker = entry.substring(0, separator).trim();
            var weight = entry.substring(separator + 1).trim();
            try {
                parsed.put(ranker, Double.valueOf(weight));
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException(
                        "--weights value for '" + ranker + "' is not a number: '" + weight + "'");
            }
        }
        return parsed;
    }

    private Integer usage(String message) {
        if (spec == null) {
            System.err.println(message);
        } else {
            spec.commandLine().getErr().println(message);
        }
        return CommandLine.ExitCode.USAGE;
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

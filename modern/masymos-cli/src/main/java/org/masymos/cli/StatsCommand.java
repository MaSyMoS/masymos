package org.masymos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "stats",
        mixinStandardHelpOptions = true,
        description = "Show database statistics through the MaSyMoS service."
)
public class StatsCommand implements Callable<Integer> {
    private final HttpRequestSender requestSender;

    @ParentCommand
    private MaSyMoSCommand root;

    @Spec
    private CommandSpec spec;

    public StatsCommand() {
        this.requestSender = new JavaHttpRequestSender(() -> ServiceHttp.client(spec));
    }

    StatsCommand(HttpClient httpClient) {
        this(new JavaHttpRequestSender(() -> httpClient));
    }

    StatsCommand(HttpRequestSender requestSender) {
        this.requestSender = requestSender;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var request = ServiceHttp.requestBuilder(spec)
                .timeout(ServiceHttp.requestTimeout(spec))
                .uri(URI.create(apiUrl() + "/api/v1/stats"))
                .GET()
                .build();

        var response = requestSender.send(request);
        output().println(response.body());
        return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1;
    }

    private String apiUrl() {
        return root == null ? "http://localhost:8080" : root.apiUrl;
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

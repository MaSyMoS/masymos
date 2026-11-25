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
        name = "sedml-links",
        mixinStandardHelpOptions = true,
        description = "Create explicit SED-ML model-reference and observed-variable links."
)
public class SedmlLinksCommand implements Callable<Integer> {
    private final HttpRequestSender requestSender;

    @ParentCommand
    private MaintenanceCommand maintenanceCommand;

    @Spec
    private CommandSpec spec;

    public SedmlLinksCommand() {
        this.requestSender = new JavaHttpRequestSender(() -> ServiceHttp.client(spec));
    }

    SedmlLinksCommand(HttpClient httpClient) {
        this(new JavaHttpRequestSender(() -> httpClient));
    }

    SedmlLinksCommand(HttpRequestSender requestSender) {
        this.requestSender = requestSender;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var request = ServiceHttp.requestBuilder(spec)
                .timeout(ServiceHttp.requestTimeout(spec))
                .uri(URI.create(apiUrl() + "/api/v1/maintenance/sedml/links"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        var response = requestSender.send(request);
        output().println(response.body());
        return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1;
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

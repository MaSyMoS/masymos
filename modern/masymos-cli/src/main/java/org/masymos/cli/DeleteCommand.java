package org.masymos.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "delete",
        mixinStandardHelpOptions = true,
        description = "Delete indexed models through the MaSyMoS service."
)
public class DeleteCommand implements Callable<Integer> {
    private final HttpRequestSender requestSender;

    @ParentCommand
    private MaSyMoSCommand root;

    @Spec
    private CommandSpec spec;

    @Option(names = "--uid", description = "UID of the model to delete.")
    private String uid;

    @Option(names = "--by-file", description = "Identifier of the file whose models are deleted.")
    private String fileId;

    @Option(names = "--file-id", description = "File identifier used to confirm the deletion (requires --uid).")
    private String confirmationFileId;

    @Option(names = "--version-id", description = "Version identifier used to confirm the deletion (requires --uid).")
    private String confirmationVersionId;

    public DeleteCommand() {
        this.requestSender = new JavaHttpRequestSender(() -> ServiceHttp.client(spec));
    }

    DeleteCommand(HttpClient httpClient) {
        this(new JavaHttpRequestSender(() -> httpClient));
    }

    DeleteCommand(HttpRequestSender requestSender) {
        this.requestSender = requestSender;
    }

    @Override
    public Integer call() throws IOException, InterruptedException {
        var request = ServiceHttp.requestBuilder(spec)
                .timeout(ServiceHttp.requestTimeout(spec))
                .uri(URI.create(apiUrl() + requestPath()))
                .DELETE()
                .build();

        var response = requestSender.send(request);
        output().println(response.body());
        return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1;
    }

    private String requestPath() {
        if (uid != null && fileId != null) {
            throw new ParameterException(commandLine(), "--uid and --by-file are mutually exclusive");
        }
        if (uid == null && fileId == null) {
            throw new ParameterException(commandLine(), "either --uid or --by-file is required");
        }
        if (uid == null && (confirmationFileId != null || confirmationVersionId != null)) {
            throw new ParameterException(
                    commandLine(), "--file-id and --version-id are only valid with --uid");
        }
        if (fileId != null) {
            return "/api/v1/models/by-file/" + encodePathSegment(fileId);
        }
        return "/api/v1/models/" + encodePathSegment(uid) + confirmationQuery();
    }

    private String confirmationQuery() {
        var query = new StringBuilder();
        if (confirmationFileId != null) {
            query.append("?fileId=").append(encode(confirmationFileId));
        }
        if (confirmationVersionId != null) {
            query.append(query.isEmpty() ? '?' : '&').append("versionId=").append(encode(confirmationVersionId));
        }
        return query.toString();
    }

    private String encodePathSegment(String value) {
        return encode(value);
    }

    private String encode(String value) {
        // RFC 3986 rather than form encoding: a space must be %20 in both path and query.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private CommandLine commandLine() {
        return spec == null ? new CommandLine(this) : spec.commandLine();
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

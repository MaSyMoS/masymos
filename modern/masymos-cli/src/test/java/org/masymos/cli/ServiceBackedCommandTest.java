package org.masymos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.ObjectMapper;

class ServiceBackedCommandTest {
    @Test
    void indexSbmlPostsSourceUrisToService() throws Exception {
        var sender = new RecordingIndexSender(201, "[]");
        var commandLine = new CommandLine(new IndexSbmlCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute(
                "--source",
                "file:///tmp/models/demo.sbml"
        );

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/indexes/sbml"), sender.request.uri());
        assertEquals("POST", sender.request.method());
        assertTrue(requestBody(sender.request).contains("file:///tmp/models/demo.sbml"));
    }

    @Test
    void indexAdditionalModelTypesPostSourceUrisToService() throws Exception {
        assertIndexEndpoint("cellml", "file:///tmp/models/demo.cellml");
        assertIndexEndpoint("sedml", "file:///tmp/models/demo.sedml");
        assertIndexEndpoint("owl", "file:///tmp/models/demo.owl");
    }

    @Test
    void indexCombinePostsOmexSourceUriToService() throws Exception {
        var sender = new RecordingIndexSender(
                201,
                "[{\"sourceUri\":\"file:///tmp/models/becker.omex\",\"indexedEntries\":2,"
                        + "\"models\":[{\"uid\":\"u1\",\"fileId\":\"becker1.sbml\","
                        + "\"sourceUri\":\"file:///tmp/models/becker.omex\"}],"
                        + "\"skipped\":[]}]");
        var commandLine = new CommandLine(new IndexCombineCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--source", "file:///tmp/models/becker.omex");

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/indexes/combine"), sender.request.uri());
        assertEquals("POST", sender.request.method());
        assertTrue(requestBody(sender.request).contains("file:///tmp/models/becker.omex"));
    }

    @Test
    void indexCellmlPostsExternalCmetaSelectionToService() throws Exception {
        var sender = new RecordingIndexSender(201, "[]");
        var commandLine = new CommandLine(new IndexCellmlCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute(
                "--source",
                "file:///tmp/models/demo.cellml",
                "--external-cmeta",
                "pmr"
        );

        assertEquals(0, exitCode);
        var body = requestBody(sender.request);
        assertTrue(body.contains("file:///tmp/models/demo.cellml"));
        assertTrue(body.contains("\"externalCmeta\":\"pmr\""));
    }

    @Test
    void queryModelsReturnsFailureExitCodeForServiceErrors() {
        var sender = new RecordingQuerySender(503, "{}");
        var commandLine = new CommandLine(new QueryModelsCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--keyword", "glucose");

        assertEquals(1, exitCode);
    }

    @Test
    void queryAnnotationsPostsKeywordAndTopToService() throws Exception {
        var sender = new RecordingAnnotationsQuerySender(200, "[]");
        var commandLine = new CommandLine(new QueryAnnotationsCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--keyword", "glucose", "--top", "5");

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/queries/annotations"), sender.request.uri());
        assertEquals("POST", sender.request.method());
        var body = requestBody(sender.request);
        assertTrue(body.contains("glucose"));
        assertTrue(body.contains("\"top\":5"));
    }

    @Test
    void queryPersonsPostsKeywordAndTopToService() throws Exception {
        var sender = new RecordingPersonsQuerySender(200, "[]");
        var commandLine = new CommandLine(new QueryPersonsCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--keyword", "curie", "--top", "7");

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/queries/persons"), sender.request.uri());
        assertEquals("POST", sender.request.method());
        var body = requestBody(sender.request);
        assertTrue(body.contains("curie"));
        assertTrue(body.contains("\"top\":7"));
    }

    @Test
    void queryPublicationsPostsKeywordAndTopToService() throws Exception {
        var sender = new RecordingPublicationsQuerySender(200, "[]");
        var commandLine = new CommandLine(new QueryPublicationsCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--keyword", "biomodels", "--top", "3");

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/queries/publications"), sender.request.uri());
        assertEquals("POST", sender.request.method());
        var body = requestBody(sender.request);
        assertTrue(body.contains("biomodels"));
        assertTrue(body.contains("\"top\":3"));
    }

    @Test
    void sedmlMaintenancePostsToService() {
        var sender = new RecordingSedmlMaintenanceSender(200, "{}");
        var commandLine = new CommandLine(new SedmlLinksCommand(sender));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/maintenance/sedml/links"), sender.request.uri());
        assertEquals("POST", sender.request.method());
    }

    @Test
    void annotationsIndexUsesDefaultQueryParameters() throws Exception {
        var sender = new RecordingAnnotationsMaintenanceSender(
                200, "{\"scannedResources\":4,\"indexedResources\":2,\"skippedResources\":1,\"failedResources\":1}");
        var commandLine = new CommandLine(new MaintenanceAnnotationsCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/maintenance/annotations/index"
                        + "?dropExistingIndex=false&limit=500&retryFailed=false&maxConcurrency=4"
                        + "&resolverMode=direct&refreshTerms=false"),
                sender.request.uri());
        assertEquals("POST", sender.request.method());
        assertTrue(out.toString().contains("scannedResources: 4"));
        assertTrue(out.toString().contains("failedResources: 1"));
        var terms = List.of(
                "resolverMode", "providersAvailable", "termsRequested", "providerRequests", "cacheHits",
                "cacheMisses", "unresolvedTerms", "providersDemoted", "pageRequests", "cacheFailures");
        var printed = out.toString().lines().toList();
        assertEquals(4, printed.size(), "a response without term counters adds no line: " + printed);
        for (var name : terms) {
            assertTrue(printed.stream().noneMatch(line -> line.startsWith(name + ":")), name);
        }
    }

    @Test
    void annotationsIndexForwardsConfiguredOptions() throws Exception {
        var sender = new RecordingAnnotationsMaintenanceSender(
                200, "{\"scannedResources\":9,\"indexedResources\":8,\"skippedResources\":1,\"failedResources\":0}");
        var commandLine = new CommandLine(new MaintenanceAnnotationsCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute(
                "--drop-existing",
                "--retry-failed",
                "true",
                "--limit",
                "25",
                "--max-concurrency",
                "2",
                "--resolver-mode",
                "providers",
                "--refresh-terms"
        );

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/maintenance/annotations/index"
                        + "?dropExistingIndex=true&limit=25&retryFailed=true&maxConcurrency=2"
                        + "&resolverMode=providers&refreshTerms=true"),
                sender.request.uri());
        assertEquals("POST", sender.request.method());
    }

    @Test
    void annotationsIndexPrintsTermCountersOnlyWhenTheResponseCarriesThem() throws Exception {
        var sender = new RecordingAnnotationsMaintenanceSender(
                200,
                "{\"scannedResources\":4,\"indexedResources\":2,\"skippedResources\":1,\"failedResources\":1,"
                        + "\"resolverMode\":\"providers\",\"providersAvailable\":[\"bioportal\",\"ols4\"],"
                        + "\"termsRequested\":12,\"providerRequests\":2,\"cacheHits\":7,\"cacheMisses\":5,"
                        + "\"unresolvedTerms\":1,\"providersDemoted\":[],\"pageRequests\":0,\"cacheFailures\":0}");
        var commandLine = new CommandLine(new MaintenanceAnnotationsCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--resolver-mode", "providers", "--refresh-terms", "true");

        assertEquals(0, exitCode);
        assertEquals("providers", queryParameter(sender.request, "resolverMode"));
        assertEquals("true", queryParameter(sender.request, "refreshTerms"));
        assertEquals(
                defaultRunOutput()
                        + row("resolverMode", "providers")
                        + row("providersAvailable", "bioportal,ols4")
                        + row("termsRequested", "12")
                        + row("providerRequests", "2")
                        + row("cacheHits", "7")
                        + row("cacheMisses", "5")
                        + row("unresolvedTerms", "1")
                        + row("pageRequests", "0")
                        + row("cacheFailures", "0")
                        + row("providersDemoted", ""),
                out.toString(),
                "present fields are printed even when zero, absent ones are not printed");
    }

    /** The four counter lines every annotation maintenance run printed before the term stage. */
    private static String defaultRunOutput() {
        return row("scannedResources", "4")
                + row("indexedResources", "2")
                + row("skippedResources", "1")
                + row("failedResources", "1");
    }

    private static String row(String name, String value) {
        return name + ": " + value + System.lineSeparator();
    }

    @Test
    void annotationsIndexPrintsProblemDetailUnchangedOnError() {
        var problemDetail = "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,"
                + "\"detail\":\"providers mode needs BIOPORTAL_BASE_URL/BIOPORTAL_API_KEY or OLS_BASE_URL\"}";
        var sender = new RecordingAnnotationsMaintenanceSender(409, problemDetail);
        var commandLine = new CommandLine(new MaintenanceAnnotationsCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        var err = new StringWriter();
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("--resolver-mode", "providers");

        assertEquals(1, exitCode);
        assertEquals("", out.toString(), "a failure goes to the error stream, never to standard output");
        var printed = err.toString().lines().toList();
        assertEquals(2, printed.size(), "the status line and the body, nothing else");
        assertTrue(printed.get(0).endsWith("HTTP status 409"));
        assertEquals(problemDetail, printed.get(1), "the problem detail is surfaced verbatim, never parsed");
    }

    private String queryParameter(HttpRequest request, String name) {
        var query = request.uri().getRawQuery();
        for (var parameter : query.split("&")) {
            var pair = parameter.split("=", 2);
            if (pair[0].equals(name)) {
                return pair.length == 2 ? pair[1] : "";
            }
        }
        throw new AssertionError("no query parameter " + name + " in " + query);
    }

    @Test
    void rootRegistersDeleteStatsAndAnalyzeCommands() {
        var commandLine = new CommandLine(new MaSyMoSCommand());
        var subcommands = commandLine.getSubcommands();

        assertTrue(subcommands.containsKey("delete"));
        assertTrue(subcommands.containsKey("stats"));
        assertTrue(subcommands.containsKey("analyze"));
        assertEquals(DeleteCommand.class, subcommands.get("delete").getCommandSpec().userObject().getClass());
        assertEquals(StatsCommand.class, subcommands.get("stats").getCommandSpec().userObject().getClass());
        assertEquals(AnalyzeCommand.class, subcommands.get("analyze").getCommandSpec().userObject().getClass());
    }

    @Test
    void deleteWithUidSendsQueryGuardParameters() {
        var sender = new RecordingDeleteSender(200, "{\"deleted\":1}");
        var commandLine = new CommandLine(new DeleteCommand(sender));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--uid", "U 1", "--file-id", "F 2", "--version-id", "V 3");

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/models/U%201?fileId=F%202&versionId=V%203"),
                sender.request.uri());
        assertEquals("DELETE", sender.request.method());
    }

    @Test
    void deleteByFileSendsDeleteToByFileEndpoint() {
        var sender = new RecordingDeleteSender(200, "{\"deleted\":1}");
        var commandLine = new CommandLine(new DeleteCommand(sender));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--by-file", "becker1.sbml");

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/models/by-file/becker1.sbml"),
                sender.request.uri());
        assertEquals("DELETE", sender.request.method());
    }

    @Test
    void deleteWithUidAndByFileFailsWithoutSending() {
        var sender = new RecordingDeleteSender(200, "{}");
        var commandLine = new CommandLine(new DeleteCommand(sender));
        commandLine.setErr(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--uid", "u1", "--by-file", "f1");

        assertEquals(2, exitCode);
        assertTrue(sender.request == null);
    }

    @Test
    void statsGetsStatsAndPrintsBody() {
        var sender = new RecordingStatsSender(200, "{\"models\":3}");
        var commandLine = new CommandLine(new StatsCommand(sender));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/stats"), sender.request.uri());
        assertEquals("GET", sender.request.method());
        assertTrue(out.toString().contains("{\"models\":3}"));
    }

    @Test
    void statsReturnsFailureForServiceErrors() {
        var sender = new RecordingStatsSender(500, "{\"error\":\"boom\"}");
        var commandLine = new CommandLine(new StatsCommand(sender));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        assertEquals(1, commandLine.execute());
    }

    @Test
    void analyzeGetsIndexesAndPrintsBody() {
        var sender = new RecordingAnalyzeSender(200, "[]");
        var commandLine = new CommandLine(new AnalyzeCommand(sender));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/indexes"), sender.request.uri());
        assertEquals("GET", sender.request.method());
        assertTrue(out.toString().contains("[]"));
    }

    @Test
    void analyzeReturnsFailureForServiceErrors() {
        var sender = new RecordingAnalyzeSender(500, "{\"error\":\"boom\"}");
        var commandLine = new CommandLine(new AnalyzeCommand(sender));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        assertEquals(1, commandLine.execute());
    }

    @Test
    void indexDirectoryExpandsModelFilesAsSortedFileUris(@TempDir Path directory) throws Exception {
        Files.createDirectories(directory.resolve("nested"));
        var b = Files.writeString(directory.resolve("b.sbml"), "<sbml/>");
        var a = Files.writeString(directory.resolve("a.xml"), "<xml/>");
        var c = Files.writeString(directory.resolve("nested/c.cellml"), "<cellml/>");
        Files.writeString(directory.resolve("d.sedml"), "<sedml/>");
        Files.writeString(directory.resolve("e.owl"), "<owl/>");
        Files.writeString(directory.resolve("ignored.txt"), "text");
        Files.writeString(directory.resolve("ignored.json"), "{}");

        var sender = new RecordingIndexSender(201, "[]");
        var commandLine = new CommandLine(new IndexSbmlCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--directory", directory.toString());

        assertEquals(0, exitCode);
        var body = requestBody(sender.request);
        assertTrue(body.contains(a.toUri().toString()));
        assertTrue(body.contains(b.toUri().toString()));
        assertTrue(body.contains(c.toUri().toString()));
        assertTrue(body.contains(directory.resolve("d.sedml").toUri().toString()));
        assertTrue(body.contains(directory.resolve("e.owl").toUri().toString()));
        assertTrue(!body.contains("ignored"));
        assertEquals("file", URI.create(firstSource(body)).getScheme());
        assertTrue(body.indexOf(a.toUri().toString()) < body.indexOf(b.toUri().toString()));
    }

    @Test
    void indexSourceAndDirectoryMergeSourcesFirst(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("a.xml"), "<xml/>");

        var sender = new RecordingIndexSender(201, "[]");
        var commandLine = new CommandLine(new IndexSbmlCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute(
                "--source", "file:///tmp/models/demo.sbml",
                "--directory", directory.toString());

        assertEquals(0, exitCode);
        var body = requestBody(sender.request);
        assertTrue(body.indexOf("file:///tmp/models/demo.sbml")
                < body.indexOf(directory.resolve("a.xml").toUri().toString()));
    }

    @Test
    void indexWithoutSourceAndDirectoryFails() {
        var sender = new RecordingIndexSender(201, "[]");
        var commandLine = new CommandLine(new IndexSbmlCommand(sender, new ObjectMapper()));
        var err = new StringWriter();
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute();

        assertEquals(2, exitCode);
        assertTrue(err.toString().contains("--source"));
        assertTrue(err.toString().contains("--directory"));
        assertTrue(sender.request == null);
    }

    @Test
    void indexDumpPathWritesBodyAndQuietSuppressesOutput(@TempDir Path directory) throws Exception {
        var dumpFile = directory.resolve("out/response.json");

        var dumpingSender = new RecordingIndexSender(201, "{\"indexed\":1}");
        var dumpingCommandLine = new CommandLine(new IndexSbmlCommand(dumpingSender, new ObjectMapper()));
        dumpingCommandLine.setOut(new PrintWriter(new StringWriter()));

        int dumpExitCode = dumpingCommandLine.execute(
                "--source", "file:///tmp/models/demo.sbml",
                "--dump-path", dumpFile.toString());

        assertEquals(0, dumpExitCode);
        assertEquals("{\"indexed\":1}", Files.readString(dumpFile, StandardCharsets.UTF_8));

        var quietSender = new RecordingIndexSender(201, "{\"indexed\":1}");
        var quietCommandLine = new CommandLine(new IndexSbmlCommand(quietSender, new ObjectMapper()));
        var quietOut = new StringWriter();
        quietCommandLine.setOut(new PrintWriter(quietOut));

        int quietExitCode = quietCommandLine.execute(
                "--source", "file:///tmp/models/demo.sbml",
                "--quiet");

        assertEquals(0, quietExitCode);
        assertEquals("", quietOut.toString());
    }

    private String firstSource(String body) {
        var marker = "\"sources\":[\"";
        var start = body.indexOf(marker) + marker.length();
        return body.substring(start, body.indexOf('"', start));
    }

    private String requestBody(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        var subscriber = new StringBodySubscriber();
        publisher.subscribe(subscriber);
        return subscriber.body();
    }

    private void assertIndexEndpoint(String endpoint, String sourceUri) throws Exception {
        var sender = new RecordingIndexSender(201, "[]");
        var command = switch (endpoint) {
            case "cellml" -> new IndexCellmlCommand(sender, new ObjectMapper());
            case "sedml" -> new IndexSedmlCommand(sender, new ObjectMapper());
            case "owl" -> new IndexOwlCommand(sender, new ObjectMapper());
            default -> throw new IllegalArgumentException("Unsupported endpoint: " + endpoint);
        };
        var commandLine = new CommandLine(command);
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--source", sourceUri);

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://localhost:8080/api/v1/indexes/" + endpoint), sender.request.uri());
        assertEquals("POST", sender.request.method());
        assertTrue(requestBody(sender.request).contains(sourceUri));
    }

    private static final class RecordingIndexSender implements ServiceIndexModelCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingIndexSender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingQuerySender implements QueryModelsCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;

        private RecordingQuerySender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingSedmlMaintenanceSender implements SedmlLinksCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingSedmlMaintenanceSender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingAnnotationsMaintenanceSender
            implements MaintenanceAnnotationsCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingAnnotationsMaintenanceSender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingAnnotationsQuerySender implements QueryAnnotationsCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingAnnotationsQuerySender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingPersonsQuerySender implements QueryPersonsCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingPersonsQuerySender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingPublicationsQuerySender implements QueryPublicationsCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingPublicationsQuerySender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingDeleteSender implements DeleteCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingDeleteSender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingStatsSender implements StatsCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingStatsSender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class RecordingAnalyzeSender implements AnalyzeCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingAnalyzeSender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
        }
    }

    private static final class StringBodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            var bytes = new byte[item.remaining()];
            item.get(bytes);
            try {
                output.write(bytes);
            } catch (IOException exception) {
                error = exception;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        private String body() throws Exception {
            if (!done.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("request body publisher did not complete");
            }
            if (error != null) {
                throw new IllegalStateException("failed to read request body", error);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private record FakeHttpResponse(int statusCode, String body, HttpRequest request) implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}

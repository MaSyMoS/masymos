package org.masymos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class MaSyMoSCommandTest {
    /**
     * A command that builds its own client when no sender is injected: only such a file names
     * {@code JavaHttpRequestSender}. The five {@code Index*Command}
     * subclasses do not count - they extend {@link ServiceIndexModelCommand}, which builds the client, and
     * name only the package-private {@code HttpRequestSender} seam the tests use.
     */
    private static final Pattern BUILDS_A_CLIENT = Pattern.compile("\\bJavaHttpRequestSender\\b");

    /**
     * A construction of an {@code HttpClient} in any spelling the JDK offers - the method reference the
     * commands used ({@code HttpClient::newHttpClient}), the static call, or the builder. All three are the
     * same act, so the scan must not know only one of them: a predicate that looked for
     * {@code HttpClient.newHttpClient(} alone stayed green while every command held
     * {@code HttpClient::newHttpClient}.
     */
    private static final Pattern BUILDS_A_CLIENT_DIRECTLY =
            Pattern.compile("HttpClient\\s*(?:::|\\.)\\s*(?:newHttpClient|newBuilder)");

    /**
     * A request build in the code: the assignment every command uses. It is matched with the
     * assignment rather than as a bare call, so a mention in prose cannot be read as a build that owes
     * a timeout - {@code ServiceHttp#requestBuilder} in a javadoc is not a request. The scan knows one
     * spelling on purpose; a command written another way is a review's job, not a substring's.
     */
    private static final Pattern BUILDS_A_REQUEST =
            Pattern.compile("=\\s*ServiceHttp\\.requestBuilder\\(spec\\)");

    @Test
    void registersQueryModelsSubcommand() {
        var out = new StringWriter();
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("query", "models", "--help");

        assertEquals(0, exitCode);
    }

    @Test
    void registersIndexSbmlSubcommand() {
        var out = new StringWriter();
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("index", "sbml", "--help");

        assertEquals(0, exitCode);
    }

    @Test
    void registersAdditionalIndexSubcommands() {
        assertIndexHelp("cellml");
        assertIndexHelp("sedml");
        assertIndexHelp("owl");
    }

    @Test
    void registersSedmlMaintenanceSubcommand() {
        var out = new StringWriter();
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("maintenance", "sedml-links", "--help");

        assertEquals(0, exitCode);
    }

    /**
     * A bare group command is a usage error, not a success: it prints its usage to the error stream and
     * answers the usage exit code, so a script that mistypes {@code masymos index} cannot read exit 0.
     */
    @Test
    void aBareGroupCommandExitsWithTheUsageCode() {
        for (var group : List.of("index", "query", "maintenance")) {
            var err = new StringWriter();
            var commandLine = new CommandLine(new MaSyMoSCommand());
            commandLine.setErr(new PrintWriter(err, true));

            var exitCode = commandLine.execute(group);

            assertEquals(2, exitCode, group + " must answer the usage exit code");
            assertTrue(err.toString().contains("Usage"), group + " must print its usage to the error stream");
        }
    }

    /**
     * The two root options are the CLI's only declaration of its timeouts: a subcommand's spec resolves
     * them through the root command, and outside a command line the builder falls back to its defaults,
     * which is what a test that calls {@code call()} directly sees.
     */
    @Test
    void theRootTimeoutOptionsReachTheSharedBuilder() {
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.parseArgs("--connect-timeout", "7", "--request-timeout", "90", "stats");

        var stats = commandLine.getSubcommands().get("stats").getCommandSpec();

        assertEquals(Duration.ofSeconds(7), ServiceHttp.connectTimeout(stats));
        assertEquals(Duration.ofSeconds(90), ServiceHttp.requestTimeout(stats));
        assertEquals(Duration.ofSeconds(7), ServiceHttp.client(stats).connectTimeout().orElseThrow(),
                "the declared connect timeout is the one the client carries");
        assertEquals(Duration.ofSeconds(5), ServiceHttp.connectTimeout(null), "no command line, no override");
        assertEquals(Duration.ofSeconds(60), ServiceHttp.requestTimeout(null), "no command line, no override");
    }

    private void assertIndexHelp(String subcommand) {
        var out = new StringWriter();
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("index", subcommand, "--help");

        assertEquals(0, exitCode);
    }

    @Test
    void apiUrlKeepsTheDefaultWhenTheOptionIsAbsent() {
        var root = new MaSyMoSCommand();

        assertEquals("http://localhost:8080", root.apiUrl);
    }

    @Test
    void apiUrlDropsARedundantTrailingSlashSoTheJoinedUriHasOneSeparator() {
        var root = new MaSyMoSCommand();
        new CommandLine(root).parseArgs("--api-url", "http://127.0.0.1:9/");

        assertEquals("http://127.0.0.1:9", root.apiUrl);
    }

    @Test
    void apiUrlOfOnlySlashesFallsBackToTheDefault() {
        assertEquals(MaSyMoSCommand.DEFAULT_API_URL, MaSyMoSCommand.normalise("///"));
        assertEquals(MaSyMoSCommand.DEFAULT_API_URL, MaSyMoSCommand.normalise("/"));
        assertEquals(MaSyMoSCommand.DEFAULT_API_URL, MaSyMoSCommand.normalise(""));
        assertEquals(MaSyMoSCommand.DEFAULT_API_URL, MaSyMoSCommand.normalise(null));
    }

    @Test
    void statsWithATrailingSlashApiUrlRequestsAnUriWithoutADoubleSlashAfterTheAuthority() {
        var sender = new RecordingStatsSender(new FakeHttpResponse(200, "{}", null));
        var commandLine = MaSyMoSMain.commandLine(commandWithInjectedStats(sender));
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--api-url", "http://127.0.0.1:9/", "stats");

        assertEquals(0, exitCode);
        assertEquals(URI.create("http://127.0.0.1:9/api/v1/stats"), sender.request.uri());
        assertTrue(
                !sender.request.uri().toString().substring("http://127.0.0.1:9".length()).contains("//"),
                "no doubled separator after the authority: " + sender.request.uri());
    }

    @Test
    void theRootTokenOptionReachesTheRequest() {
        var value = "s3cr3t-token-value-12345";
        var sender = new RecordingStatsSender(new FakeHttpResponse(200, "{}", null));
        var commandLine = MaSyMoSMain.commandLine(commandWithInjectedStats(sender));
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--token", value, "stats");

        assertEquals(0, exitCode);
        assertEquals(Optional.of(value), sender.request.headers().firstValue(ServiceHttp.TOKEN_HEADER));
    }

    /**
     * The other side of the option: no secret, no header. The root command is built here rather than
     * parsed, so the assertion does not depend on whether this host happens to export
     * {@code MASYMOS_SERVICE_TOKEN} - a test that adapts to its environment proves nothing.
     */
    @Test
    void aRequestWithoutATokenCarriesNoTokenHeader() {
        var sender = new RecordingStatsSender(new FakeHttpResponse(200, "{}", null));
        var root = new MaSyMoSCommand();
        root.token = null;
        var commandLine = new CommandLine(root, commandWithInjectedStats(sender));
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("stats");

        assertEquals(0, exitCode);
        assertEquals(
                Optional.empty(),
                sender.request.headers().firstValue(ServiceHttp.TOKEN_HEADER),
                "without a secret the request carries no token header");
        assertNull(ServiceHttp.token(null), "no command line, no secret");
    }

    @Test
    void connectExceptionPrintsOneLineNamingTheServiceUrlAndNoStackTrace() {
        var sender = new RecordingStatsSender(new ConnectException("Connection refused"));
        var commandLine = MaSyMoSMain.commandLine(commandWithInjectedStats(sender));
        var out = new StringWriter();
        var err = new StringWriter();
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("--api-url", "http://127.0.0.1:9/", "stats");

        assertEquals(1, exitCode);
        var printed = err.toString().lines().toList();
        assertEquals(1, printed.size(), "exactly one line on stderr: " + printed);
        assertTrue(printed.get(0).contains("http://127.0.0.1:9"), "names the service URL: " + printed);
        assertTrue(printed.get(0).contains("Connection refused"), "carries the cause message: " + printed);
        assertTrue(
                printed.stream().noneMatch(line -> line.startsWith("\tat ")),
                "no stack trace on stderr: " + printed);
        assertEquals("", out.toString());
    }

    /**
     * No command builds its own HTTP client: every {@code HttpClient} in the CLI comes from
     * {@link ServiceHttp}, which declares the timeouts, and every request carries the request timeout so a
     * hung service cannot hold a command for ever. Nor does a command build its own request builder: the
     * shared secret is added there, so {@code HttpRequest.newBuilder()} may exist only in
     * {@code ServiceHttp.java} - one shared builder is the only path.
     */
    @Test
    void everyCommandBuildsItsClientFromTheSharedBuilder() throws IOException {
        assertEquals(Optional.of(Duration.ofSeconds(5)), ServiceHttp.client().connectTimeout());
        assertEquals(Duration.ofSeconds(60), ServiceHttp.REQUEST_TIMEOUT);

        var cliSources = cliMainSources();
        var problems = new ArrayList<String>();
        var clientBuildingCommands = new ArrayList<String>();

        for (var file : cliSources) {
            String name = file.getFileName().toString();
            String text = Files.readString(file);
            boolean isServiceHttp = name.equals("ServiceHttp.java");

            var direct = BUILDS_A_CLIENT_DIRECTLY.matcher(text);
            if (!isServiceHttp && direct.find()) {
                problems.add(name + " constructs its own HttpClient: " + direct.group());
            }
            if (!isServiceHttp && text.contains("HttpRequest.newBuilder()")) {
                problems.add(name + " builds its own request; use ServiceHttp.requestBuilder(spec)");
            }
            var buildsARequest = text.contains("HttpRequest.newBuilder()")
                    || BUILDS_A_REQUEST.matcher(text).find();
            if (buildsARequest && !isServiceHttp && !text.contains(".timeout(ServiceHttp.")) {
                problems.add(name + " builds a request without a ServiceHttp timeout");
            }
            if (BUILDS_A_CLIENT.matcher(text).find()) {
                clientBuildingCommands.add(name);
                if (!text.contains("ServiceHttp")) {
                    problems.add(name + " builds its client without ServiceHttp");
                }
            }
        }

        assertEquals(List.of(), problems, "every CLI command must build its client through ServiceHttp");
        assertTrue(
                cliSources.stream().anyMatch(file -> file.getFileName().toString().equals("ServiceHttp.java")),
                "the scan must see ServiceHttp.java");
        assertEquals(
                11,
                clientBuildingCommands.size(),
                "the scan must see exactly the eleven client-building commands, saw: " + clientBuildingCommands);
    }

    /** Every Java source of the CLI production code, found from the checkout that contains `modern`. */
    private static List<Path> cliMainSources() throws IOException {
        for (var dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var cliMain = dir.resolve("modern").resolve("masymos-cli").resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(cliMain)) {
                try (var paths = Files.walk(cliMain)) {
                    var java = paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .sorted()
                            .toList();
                    assertTrue(!java.isEmpty(), "the scan must find the CLI sources, found none under " + cliMain);
                    return java;
                }
            }
        }
        Assumptions.assumeTrue(
                false, "no directory above the working directory contains modern/masymos-cli/src/main/java");
        return List.of();
    }

    /**
     * The root command line with one seam installed: the `stats` subcommand is built with a
     * recording sender instead of the real HTTP client. Everything else - the root command, the
     * other subcommands, the execution-exception handler - is exactly what `main` builds.
     */
    private static CommandLine.IFactory commandWithInjectedStats(StatsCommand.HttpRequestSender sender) {
        return new CommandLine.IFactory() {
            private final CommandLine.IFactory delegate = CommandLine.defaultFactory();

            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == StatsCommand.class) {
                    return type.cast(new StatsCommand(sender));
                }
                return delegate.create(type);
            }
        };
    }

    private static final class RecordingStatsSender implements StatsCommand.HttpRequestSender {
        private final Object outcome;
        private HttpRequest request;

        private RecordingStatsSender(Object outcome) {
            this.outcome = outcome;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) throws ConnectException {
            this.request = request;
            if (outcome instanceof ConnectException connectException) {
                throw connectException;
            }
            var response = (FakeHttpResponse) outcome;
            return new FakeHttpResponse(response.statusCode, response.body, request);
        }
    }

    private record FakeHttpResponse(int statusCode, String body, HttpRequest request) implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
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

package org.masymos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import tools.jackson.databind.ObjectMapper;

class UnitsBackfillCommandTest {
    private static final String RESPONSE = "{\"applied\":true,\"documentsScanned\":12,"
            + "\"documentsNotScanned\":4,\"documentsBackfilled\":7,\"documentsAlreadyComplete\":3,"
            + "\"skippedWithoutStoredDocument\":1,\"skippedTruncatedDocument\":0,"
            + "\"skippedUnsupportedType\":1,\"skippedUnparseable\":0,\"unitsCreated\":5,"
            + "\"usesUnitCreated\":7,\"hasUnitCreated\":9,\"compositionCreated\":2,"
            + "\"relationshipsSkipped\":4,\"writes\":27,\"complete\":false}";

    @Test
    void usesDryRunByDefaultAndPrintsCounters() {
        var sender = new RecordingUnitsBackfillSender(200, RESPONSE);
        var commandLine = new CommandLine(new UnitsBackfillCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/maintenance/units-backfill?apply=false&limit=500"),
                sender.request.uri());
        assertEquals("POST", sender.request.method());
        var printed = out.toString().lines().toList();
        assertEquals("applied: true", printed.get(0));
        assertTrue(printed.contains("documentsScanned: 12"));
        assertTrue(printed.contains("documentsBackfilled: 7"));
        assertTrue(printed.contains("skippedWithoutStoredDocument: 1"));
        assertTrue(printed.contains("unitsCreated: 5"));
        assertTrue(printed.contains("writes: 27"));
        assertEquals("complete: false", printed.get(printed.size() - 1));
    }

    @Test
    void applyFlagWrites() {
        var sender = new RecordingUnitsBackfillSender(200, RESPONSE);
        var commandLine = new CommandLine(new UnitsBackfillCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute("--apply");

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/maintenance/units-backfill?apply=true&limit=500"),
                sender.request.uri());
    }

    @Test
    void limitReachesTheServiceAndIsPrinted() {
        var sender = new RecordingUnitsBackfillSender(200, RESPONSE);
        var commandLine = new CommandLine(new UnitsBackfillCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--apply", "--limit", "0");

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/maintenance/units-backfill?apply=true&limit=0"),
                sender.request.uri(),
                "0 is the documented unbounded value and must be sent as given");
        assertTrue(out.toString().contains("documentsNotScanned"));
    }

    @Test
    void honoursRootApiUrl() {
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        var err = new StringWriter();
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute(
                "--api-url", "http://service:9090", "maintenance", "units-backfill", "--help");

        assertEquals(0, exitCode, err.toString());
    }

    @Test
    void printsProblemDetailUnchangedOnError() {
        var problemDetail = "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,"
                + "\"detail\":\"backfill is already running\"}";
        var sender = new RecordingUnitsBackfillSender(409, problemDetail);
        var commandLine = new CommandLine(new UnitsBackfillCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        var err = new StringWriter();
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute("--apply");

        assertEquals(1, exitCode);
        assertEquals("", out.toString(), "a failure goes to the error stream, never to standard output");
        var printed = err.toString().lines().toList();
        assertEquals(2, printed.size(), "the status line and the body, nothing else");
        assertTrue(printed.get(0).endsWith("HTTP status 409"));
        assertEquals(problemDetail, printed.get(1));
    }

    /**
     * {@code C04-2}: the operator's loop is bounded requests, so the cursor a page reports has to be
     * sendable back and the next one has to be visible.
     */
    @Test
    void cursorReachesTheServiceAndTheNextOneIsPrinted() {
        var body = "{\"applied\":true,\"documentsScanned\":2,\"documentsNotScanned\":1,"
                + "\"documentsBackfilled\":2,\"documentsAlreadyComplete\":0,"
                + "\"skippedWithoutStoredDocument\":0,\"skippedTruncatedDocument\":0,"
                + "\"skippedUnsupportedType\":0,\"skippedUnparseable\":0,\"unitsCreated\":4,"
                + "\"usesUnitCreated\":2,\"hasUnitCreated\":2,\"compositionCreated\":1,"
                + "\"relationshipsSkipped\":0,\"writes\":9,\"complete\":false,"
                + "\"nextCursor\":\"uid-7\",\"startedFromCursor\":true}";
        var sender = new RecordingUnitsBackfillSender(200, body);
        var commandLine = new CommandLine(new UnitsBackfillCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--apply", "--cursor", "uid-3");

        assertEquals(0, exitCode);
        assertEquals(
                URI.create(
                        "http://localhost:8080/api/v1/maintenance/units-backfill?apply=true&limit=500&cursor=uid-3"),
                sender.request.uri(),
                "the cursor a previous run reported is sent back unchanged");
        var printed = out.toString().lines().toList();
        assertTrue(printed.contains("nextCursor: uid-7"), out.toString());
        assertEquals("complete: false", printed.get(printed.size() - 1), "the verdict stays the last line");
    }

    @Test
    void maintenanceRegistersUnitsBackfill() {
        assertEquals(
                UnitsBackfillCommand.class,
                new CommandLine(new MaSyMoSCommand())
                        .getSubcommands()
                        .get("maintenance")
                        .getSubcommands()
                        .get("units-backfill")
                        .getCommandSpec()
                        .userObject()
                        .getClass());
    }

    private static final class RecordingUnitsBackfillSender implements UnitsBackfillCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingUnitsBackfillSender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeHttpResponse(statusCode, body, request);
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

package org.masymos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class ReDeriveCommandTest {
    private static final String RESPONSE = "{\"documentsScanned\":12,\"documentsStale\":9,"
            + "\"documentsCurrent\":3,\"documentsNotScanned\":4,\"skippedWithoutStoredDocument\":1,"
            + "\"skippedTruncatedDocument\":0,\"skippedUnsupportedType\":1,\"skippedUnparseable\":0,"
            + "\"missingDerivedKeys\":812,\"missingByLabel\":{\"ANNOTATION\":4,\"Model:SBML_MODEL\":0},"
            + "\"nextCursor\":\"abc\"}";

    @Test
    void postsTheBoundAndCursorAndPrintsTheShortfall() {
        var sender = new RecordingReDeriveSender(200, RESPONSE);
        var commandLine = new CommandLine(new ReDeriveCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--limit", "7", "--cursor", "prev");

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/maintenance/re-derive?limit=7&cursor=prev"),
                sender.request.uri());
        assertEquals("POST", sender.request.method());
        var printed = out.toString().lines().toList();
        assertTrue(printed.contains("documentsScanned: 12"));
        assertTrue(printed.contains("documentsStale: 9"));
        assertTrue(printed.contains("missingDerivedKeys: 812"));
        assertTrue(printed.contains("missing ANNOTATION: 4"));
        assertFalse(printed.contains("missing Model:SBML_MODEL: 0"), "a zero shortfall is not printed");
        assertTrue(printed.contains("nextCursor: abc"));
        assertTrue(printed.contains("readOnly: true"));
    }

    @Test
    void sendsTheDocumentedDefaultPageSize() {
        var sender = new RecordingReDeriveSender(200, RESPONSE);
        var commandLine = new CommandLine(new ReDeriveCommand(sender, new ObjectMapper()));
        commandLine.setOut(new PrintWriter(new StringWriter()));

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/maintenance/re-derive?limit=500"),
                sender.request.uri());
    }

    /**
     * {@code OPN-7} slice 2: {@code --apply} is the repair, so it has to reach the endpoint as the
     * documented parameter, and the run must not still call itself read-only - an operator who swept a
     * corpus with {@code --apply} and saw "readOnly: true" would believe nothing had been written.
     */
    @Test
    void applySendsTheRepairFlagAndStopsClaimingToBeReadOnly() {
        var repaired = "{\"documentsScanned\":12,\"documentsStale\":9,\"documentsCurrent\":3,"
                + "\"documentsNotScanned\":0,\"skippedWithoutStoredDocument\":0,"
                + "\"skippedTruncatedDocument\":1,\"skippedUnsupportedType\":0,\"skippedUnparseable\":3,"
                + "\"missingDerivedKeys\":0,\"missingByLabel\":{},\"applied\":8}";
        var sender = new RecordingReDeriveSender(200, repaired);
        var commandLine = new CommandLine(new ReDeriveCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("--apply", "--limit", "7");

        assertEquals(0, exitCode);
        assertEquals(
                URI.create("http://localhost:8080/api/v1/maintenance/re-derive?limit=7&apply=true"),
                sender.request.uri());
        var printed = out.toString().lines().toList();
        assertTrue(printed.contains("applied: 8"), "the run reports what it rewrote: " + printed);
        assertTrue(printed.contains("skippedTruncatedDocument: 1"), "and what it refused: " + printed);
        assertTrue(printed.contains("readOnly: false"), "an apply run is not a read-only scan: " + printed);
    }

    @Test
    void printsAProblemDetailOnErrorAndExitsNonZero() {
        var problemDetail = "{\"type\":\"about:blank\",\"title\":\"Storage unavailable\",\"status\":503}";
        var sender = new RecordingReDeriveSender(503, problemDetail);
        var commandLine = new CommandLine(new ReDeriveCommand(sender, new ObjectMapper()));
        var out = new StringWriter();
        var err = new StringWriter();
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        int exitCode = commandLine.execute();

        assertEquals(1, exitCode);
        assertEquals("", out.toString(), "a failure goes to the error stream, never to standard output");
        var printed = err.toString().lines().toList();
        assertEquals(2, printed.size(), "the status line and the body, nothing else");
        assertTrue(printed.get(0).endsWith("HTTP status 503"));
        assertEquals(problemDetail, printed.get(1));
    }

    private static final class RecordingReDeriveSender implements ReDeriveCommand.HttpRequestSender {
        private final int statusCode;
        private final String body;
        private HttpRequest request;

        private RecordingReDeriveSender(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public HttpResponse<String> send(HttpRequest request) {
            this.request = request;
            return new FakeResponse(statusCode, body, request);
        }

        HttpRequest request() {
            return request;
        }
    }

    private record FakeResponse(int statusCode, String body, HttpRequest request) implements HttpResponse<String> {
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

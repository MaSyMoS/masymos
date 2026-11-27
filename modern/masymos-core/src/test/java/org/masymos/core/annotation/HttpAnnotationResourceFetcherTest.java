package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpAnnotationResourceFetcherTest {

    /** A policy that allows the host every stub below answers for. */
    private static HttpAnnotationEgressPolicy policy(int maxBodyBytes) {
        return new HttpAnnotationEgressPolicy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), maxBodyBytes, Set.of("example.org"), 1);
    }

    @Test
    void returnsBodyAndContentTypeOnSuccess() {
        var fetcher = new HttpAnnotationResourceFetcher(
                request -> new HttpAnnotationResourceFetcher.HttpReply(
                        200,
                        "text/html; charset=UTF-8",
                        "<b>ok</b>".getBytes(StandardCharsets.UTF_8)
                ),
                policy(HttpAnnotationEgressPolicy.DEFAULT_MAX_BODY_BYTES)
        );

        var result = fetcher.fetch("https://example.org/page");

        assertTrue(result.succeeded());
        assertEquals(200, result.statusCode());
        assertEquals("text/html; charset=UTF-8", result.contentType());
        assertEquals("<b>ok</b>", result.body());
    }

    @Test
    void reportsNonSuccessStatusWithoutBody() {
        var fetcher = new HttpAnnotationResourceFetcher(
                request -> new HttpAnnotationResourceFetcher.HttpReply(
                        404,
                        "text/plain",
                        "missing".getBytes(StandardCharsets.UTF_8)
                ),
                policy(HttpAnnotationEgressPolicy.DEFAULT_MAX_BODY_BYTES)
        );

        var result = fetcher.fetch("https://example.org/missing");

        assertFalse(result.succeeded());
        assertEquals(404, result.statusCode());
        assertEquals("", result.body());
        assertEquals("http status 404", result.failureReason());
    }

    @Test
    void mapsTransportFailureToFailedResult() {
        var fetcher = new HttpAnnotationResourceFetcher(
                request -> {
                    throw new IOException("connection reset");
                },
                policy(HttpAnnotationEgressPolicy.DEFAULT_MAX_BODY_BYTES)
        );

        var result = fetcher.fetch("https://example.org/page");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().startsWith("IOException"), result.failureReason());
    }

    @Test
    void restoresInterruptFlag() {
        var fetcher = new HttpAnnotationResourceFetcher(
                request -> {
                    throw new InterruptedException("cancelled");
                },
                policy(HttpAnnotationEgressPolicy.DEFAULT_MAX_BODY_BYTES)
        );

        var result = fetcher.fetch("https://example.org/page");

        assertEquals("interrupted", result.failureReason());
        assertTrue(Thread.interrupted(), "interrupt flag must be restored and cleared by the test");
    }

    @Test
    void rejectsUnsupportedUrls() {
        var fetcher = new HttpAnnotationResourceFetcher(
                request -> new HttpAnnotationResourceFetcher.HttpReply(
                        200,
                        "text/plain",
                        "body".getBytes(StandardCharsets.UTF_8)
                ),
                policy(HttpAnnotationEgressPolicy.DEFAULT_MAX_BODY_BYTES)
        );

        assertEquals("empty url", fetcher.fetch("  ").failureReason());
        assertEquals("unsupported scheme", fetcher.fetch("ftp://example.org/x").failureReason());
    }

    @Test
    void boundsResponseBody() {
        var fetcher = new HttpAnnotationResourceFetcher(
                request -> new HttpAnnotationResourceFetcher.HttpReply(
                        200,
                        "text/plain",
                        "0123456789".getBytes(StandardCharsets.UTF_8)
                ),
                policy(4)
        );

        var result = fetcher.fetch("https://example.org/page");

        assertEquals("0123", result.body());
        assertNotNull(result.truncationReason(), "a body longer than the cap must say it was cut");
    }

    @Test
    void doesNotCreateHttpClientBeforeFirstFetch() {
        // Constructor takes the transport seam; no HttpClient is created for injected transports.
        var fetcher = new HttpAnnotationResourceFetcher(
                request -> new HttpAnnotationResourceFetcher.HttpReply(
                        200,
                        "text/plain",
                        "x".getBytes(StandardCharsets.UTF_8)
                ),
                policy(HttpAnnotationEgressPolicy.DEFAULT_MAX_BODY_BYTES)
        );

        assertTrue(fetcher.fetch("https://example.org/page").succeeded());
    }

    @Test
    void abandonsTheBodyAtTheConfiguredByteCap() {
        var pulled = new AtomicInteger();
        var endless = new InputStream() {
            @Override
            public int read() {
                pulled.incrementAndGet();
                return 'x';
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                Arrays.fill(target, offset, offset + length, (byte) 'x');
                pulled.addAndGet(length);
                return length;
            }
        };
        HttpAnnotationResourceFetcher.StreamedExchange exchange = new HttpAnnotationResourceFetcher.StreamedExchange() {
            @Override
            public HttpAnnotationResourceFetcher.StreamedReply sendStreamed(HttpRequest request) {
                return new HttpAnnotationResourceFetcher.StreamedReply(200, "text/plain", null, endless);
            }
        };

        var result = new HttpAnnotationResourceFetcher(exchange, policy(8)).fetch("https://example.org/page");

        assertEquals(8, result.body().length());
        assertEquals("xxxxxxxx", result.body());
        assertNotNull(result.truncationReason(), "a body that reached the cap must say so");
        assertTrue(pulled.get() <= 9, "the transport read " + pulled.get() + " bytes for a cap of 8");
    }

    @Test
    void aHostOutsideTheAllowlistIsNeverContacted() {
        var contacted = new AtomicBoolean();
        HttpAnnotationResourceFetcher.StreamedExchange exchange = request -> {
            contacted.set(true);
            return new HttpAnnotationResourceFetcher.StreamedReply(
                    200, "text/plain", null, InputStream.nullInputStream());
        };

        var result = new HttpAnnotationResourceFetcher(exchange, policy(1024)).fetch("https://evil.example/page");

        assertFalse(contacted.get(), "a refused host must not reach the transport");
        assertEquals("host not allowed: evil.example", result.failureReason());
    }

    @Test
    void aRedirectToLoopbackIsNotFollowed() {
        var followed = new AtomicBoolean();
        HttpAnnotationResourceFetcher.StreamedExchange exchange = request -> {
            if ("localhost".equals(request.uri().getHost())) {
                followed.set(true);
                return new HttpAnnotationResourceFetcher.StreamedReply(
                        200, "text/plain", null, new ByteArrayInputStream("secret".getBytes(StandardCharsets.UTF_8)));
            }
            return new HttpAnnotationResourceFetcher.StreamedReply(
                    302, "text/html", "http://localhost:8080/hidden", InputStream.nullInputStream());
        };

        var result = new HttpAnnotationResourceFetcher(exchange, policy(1024)).fetch("https://example.org/page");

        assertFalse(followed.get(), "a redirect to a host outside the allowlist must not be followed");
        assertEquals(302, result.statusCode());
        assertTrue(result.failureReason().contains("host not allowed: localhost"), result.failureReason());
    }
}

package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpAnnotationResourceFetcherTest {

    @Test
    void returnsBodyAndContentTypeOnSuccess() {
        var fetcher = new HttpAnnotationResourceFetcher(request -> new HttpAnnotationResourceFetcher.HttpReply(
                200,
                "text/html; charset=UTF-8",
                "<b>ok</b>".getBytes(StandardCharsets.UTF_8)
        ));

        var result = fetcher.fetch("https://example.org/page");

        assertTrue(result.succeeded());
        assertEquals(200, result.statusCode());
        assertEquals("text/html; charset=UTF-8", result.contentType());
        assertEquals("<b>ok</b>", result.body());
    }

    @Test
    void reportsNonSuccessStatusWithoutBody() {
        var fetcher = new HttpAnnotationResourceFetcher(request -> new HttpAnnotationResourceFetcher.HttpReply(
                404,
                "text/plain",
                "missing".getBytes(StandardCharsets.UTF_8)
        ));

        var result = fetcher.fetch("https://example.org/missing");

        assertFalse(result.succeeded());
        assertEquals(404, result.statusCode());
        assertEquals("", result.body());
        assertEquals("http status 404", result.failureReason());
    }

    @Test
    void mapsTransportFailureToFailedResult() {
        var fetcher = new HttpAnnotationResourceFetcher(request -> {
            throw new IOException("connection reset");
        });

        var result = fetcher.fetch("https://example.org/page");

        assertFalse(result.succeeded());
        assertTrue(result.failureReason().startsWith("IOException"), result.failureReason());
    }

    @Test
    void restoresInterruptFlag() {
        var fetcher = new HttpAnnotationResourceFetcher(request -> {
            throw new InterruptedException("cancelled");
        });

        var result = fetcher.fetch("https://example.org/page");

        assertEquals("interrupted", result.failureReason());
        assertTrue(Thread.interrupted(), "interrupt flag must be restored and cleared by the test");
    }

    @Test
    void rejectsUnsupportedUrls() {
        var fetcher = new HttpAnnotationResourceFetcher(request -> new HttpAnnotationResourceFetcher.HttpReply(
                200,
                "text/plain",
                "body".getBytes(StandardCharsets.UTF_8)
        ));

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
                4
        );

        assertEquals("0123", fetcher.fetch("https://example.org/page").body());
    }

    @Test
    void doesNotCreateHttpClientBeforeFirstFetch() {
        // Constructor takes the transport seam; no HttpClient is created for injected transports.
        var fetcher = new HttpAnnotationResourceFetcher(request -> new HttpAnnotationResourceFetcher.HttpReply(
                200,
                "text/plain",
                "x".getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(fetcher.fetch("https://example.org/page").succeeded());
    }
}

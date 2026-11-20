package org.masymos.core.annotation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * HTTP fetcher for annotation candidate URLs.
 *
 * <p>The underlying {@link HttpClient} is created on the first fetch, so service startup and routine
 * tests never open network primitives. Responses are bounded by {@code maxBodyBytes}; non-2xx
 * responses and transport failures are reported as failed results instead of exceptions.
 */
public final class HttpAnnotationResourceFetcher implements AnnotationResourceFetcher {

    public static final int DEFAULT_MAX_BODY_BYTES = 512 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final int maxBodyBytes;
    private final HttpExchange exchange;
    private HttpExchange lazyExchange;

    public HttpAnnotationResourceFetcher() {
        this(null, DEFAULT_MAX_BODY_BYTES);
    }

    HttpAnnotationResourceFetcher(HttpExchange exchange) {
        this(exchange, DEFAULT_MAX_BODY_BYTES);
    }

    HttpAnnotationResourceFetcher(HttpExchange exchange, int maxBodyBytes) {
        this.exchange = exchange;
        this.maxBodyBytes = Math.max(1, maxBodyBytes);
    }

    @Override
    public AnnotationFetchResult fetch(String url) {
        if (url == null || url.isBlank()) {
            return AnnotationFetchResult.failure(url, "empty url");
        }
        var lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return AnnotationFetchResult.failure(url, "unsupported scheme");
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml,application/json,text/plain;q=0.8")
                    .GET()
                    .build();
        } catch (RuntimeException exception) {
            return AnnotationFetchResult.failure(url, "invalid url: " + exception.getMessage());
        }

        HttpReply reply;
        try {
            reply = exchange().send(request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return AnnotationFetchResult.failure(url, "interrupted");
        } catch (IOException | RuntimeException exception) {
            return AnnotationFetchResult.failure(url, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }

        if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
            return new AnnotationFetchResult(url, reply.statusCode(), reply.contentType(), "", "http status " + reply.statusCode());
        }

        var bodyBytes = reply.body() == null ? new byte[0] : reply.body();
        var bounded = bodyBytes.length > maxBodyBytes ? java.util.Arrays.copyOf(bodyBytes, maxBodyBytes) : bodyBytes;
        return new AnnotationFetchResult(url, reply.statusCode(), reply.contentType(), decode(bounded, reply.contentType()), null);
    }

    private synchronized HttpExchange exchange() {
        if (exchange != null) {
            return exchange;
        }
        if (lazyExchange == null) {
            lazyExchange = new JdkHttpExchange();
        }
        return lazyExchange;
    }

    private static String decode(byte[] body, String contentType) {
        return new String(body, charsetOf(contentType));
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (var part : contentType.split(";")) {
                var trimmed = part.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    var name = trimmed.substring("charset=".length()).trim();
                    try {
                        return Charset.forName(name);
                    } catch (RuntimeException exception) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Minimal HTTP reply shape so tests can fake transport without a network or an HttpClient mock. */
    record HttpReply(int statusCode, String contentType, byte[] body) {
    }

    /** Transport seam: the only place that touches the network. */
    @FunctionalInterface
    interface HttpExchange {
        HttpReply send(HttpRequest request) throws IOException, InterruptedException;
    }

    private static final class JdkHttpExchange implements HttpExchange {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public HttpReply send(HttpRequest request) throws IOException, InterruptedException {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            var contentType = response.headers().firstValue("content-type").orElse(null);
            return new HttpReply(response.statusCode(), contentType, response.body());
        }
    }
}

package org.masymos.core.annotation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * HTTP fetcher for annotation candidate URLs.
 *
 * <p>The underlying {@link HttpClient} is created on the first fetch, so service startup and routine
 * tests never open network primitives. Every fetch goes through one {@link
 * HttpAnnotationEgressPolicy}: the host allowlist is checked before a request is built, the body is
 * read from the wire and abandoned after {@code maxBodyBytes}, and no redirect is followed by the
 * client — at most one sanctioned hop is taken by this class, and only to an allowlisted host.
 *
 * <p>Non-2xx responses and transport failures are reported as failed results instead of exceptions. A
 * body that reached the cap is reported with a non-null {@link
 * AnnotationFetchResult#truncationReason()} and still counts as a succeeded fetch: the text it carried
 * is usable, only its tail is gone.
 */
public final class HttpAnnotationResourceFetcher implements AnnotationResourceFetcher {

    public static final int DEFAULT_MAX_BODY_BYTES = HttpAnnotationEgressPolicy.DEFAULT_MAX_BODY_BYTES;

    private final HttpAnnotationEgressPolicy policy;
    private final HttpExchange exchange;
    private HttpExchange lazyExchange;

    public HttpAnnotationResourceFetcher() {
        this(null, HttpAnnotationEgressPolicy.defaults());
    }

    public HttpAnnotationResourceFetcher(HttpAnnotationEgressPolicy policy) {
        this(null, policy);
    }

    HttpAnnotationResourceFetcher(HttpExchange exchange) {
        this(exchange, HttpAnnotationEgressPolicy.defaults());
    }

    HttpAnnotationResourceFetcher(HttpExchange exchange, int maxBodyBytes) {
        this(exchange, new HttpAnnotationEgressPolicy(
                HttpAnnotationEgressPolicy.DEFAULT_CONNECT_TIMEOUT,
                HttpAnnotationEgressPolicy.DEFAULT_READ_TIMEOUT,
                maxBodyBytes,
                HttpAnnotationEgressPolicy.DEFAULT_ALLOWED_HOSTS,
                HttpAnnotationEgressPolicy.DEFAULT_MAX_REDIRECT_HOPS));
    }

    HttpAnnotationResourceFetcher(HttpExchange exchange, HttpAnnotationEgressPolicy policy) {
        this.exchange = exchange;
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }

    /** The policy this fetcher enforces; the same instance is handed to the provider transport. */
    public HttpAnnotationEgressPolicy policy() {
        return policy;
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

        URI target;
        try {
            target = URI.create(url);
        } catch (RuntimeException exception) {
            return AnnotationFetchResult.failure(url, "invalid url: " + exception.getMessage());
        }
        var refusal = policy.refusal(target);
        if (refusal.isPresent()) {
            return AnnotationFetchResult.failure(url, refusal.get());
        }

        var hops = 0;
        var current = target;
        while (true) {
            HttpRequest request;
            try {
                request = requestFor(current);
            } catch (RuntimeException exception) {
                return AnnotationFetchResult.failure(url, "invalid url: " + exception.getMessage());
            }

            StreamedReply reply;
            try {
                reply = sendStreamed(request);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return AnnotationFetchResult.failure(url, "interrupted");
            } catch (IOException | RuntimeException exception) {
                return AnnotationFetchResult.failure(
                        url, exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }

            if (isRedirect(reply.statusCode())) {
                closeQuietly(reply.body());
                var location = reply.location();
                if (location == null) {
                    return new AnnotationFetchResult(
                            url, reply.statusCode(), reply.contentType(), "", "http status " + reply.statusCode());
                }
                if (hops >= policy.maxRedirectHops()) {
                    return new AnnotationFetchResult(
                            url, reply.statusCode(), reply.contentType(), "", "redirect not followed: " + location);
                }
                URI next;
                try {
                    next = current.resolve(location);
                } catch (RuntimeException exception) {
                    return new AnnotationFetchResult(
                            url, reply.statusCode(), reply.contentType(), "", "redirect refused: unparsable " + location);
                }
                var hopRefusal = policy.refusal(next);
                if (hopRefusal.isPresent()) {
                    return new AnnotationFetchResult(
                            url, reply.statusCode(), reply.contentType(), "", "redirect refused: " + hopRefusal.get());
                }
                hops++;
                current = next;
                continue;
            }

            if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
                closeQuietly(reply.body());
                return new AnnotationFetchResult(
                        url, reply.statusCode(), reply.contentType(), "", "http status " + reply.statusCode());
            }
            return readBounded(url, reply);
        }
    }

    private HttpRequest requestFor(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(policy.readTimeout())
                .header("Accept", "text/html,application/xhtml+xml,application/xml,application/json,text/plain;q=0.8")
                .GET()
                .build();
    }

    /** Reads at most {@code maxBodyBytes} and abandons the rest instead of materialising it. */
    private AnnotationFetchResult readBounded(String url, StreamedReply reply) {
        var limit = policy.maxBodyBytes();
        var buffer = new byte[limit];
        var filled = 0;
        var truncated = false;
        try (var body = reply.body()) {
            if (body != null) {
                while (filled < limit) {
                    var read = body.read(buffer, filled, limit - filled);
                    if (read < 0) {
                        break;
                    }
                    filled += read;
                }
                if (filled == limit) {
                    truncated = body.read() >= 0;
                }
            }
        } catch (IOException exception) {
            return AnnotationFetchResult.failure(
                    url, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        var bytes = Arrays.copyOf(buffer, filled);
        var truncationReason = truncated ? "body truncated at " + limit + " bytes" : null;
        return new AnnotationFetchResult(
                url, reply.statusCode(), reply.contentType(), decode(bytes, reply.contentType()), null, truncationReason);
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (IOException ignored) {
            // Abandoning a response body: there is nothing to report and nothing to retry.
        }
    }

    private StreamedReply sendStreamed(HttpRequest request) throws IOException, InterruptedException {
        var seam = exchange();
        if (seam instanceof StreamedExchange streamed) {
            return streamed.sendStreamed(request);
        }
        var reply = seam.send(request);
        return new StreamedReply(
                reply.statusCode(), reply.contentType(), null, new ByteArrayInputStream(reply.body()));
    }

    private synchronized HttpExchange exchange() {
        if (exchange != null) {
            return exchange;
        }
        if (lazyExchange == null) {
            lazyExchange = new JdkHttpExchange(policy);
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

    /** The streamed reply: status and content type first, then the body as it arrives. */
    record StreamedReply(int statusCode, String contentType, String location, InputStream body) {
    }

    /** Transport seam: the only place that touches the network. */
    @FunctionalInterface
    interface HttpExchange {
        HttpReply send(HttpRequest request) throws IOException, InterruptedException;
    }

    /**
     * A transport that hands the body over as a stream, so the cap can abandon it instead of
     * materialising it. The real transports implement this; a test stub that only knows the whole body
     * keeps using {@link HttpExchange}.
     */
    @FunctionalInterface
    interface StreamedExchange extends HttpExchange {

        StreamedReply sendStreamed(HttpRequest request) throws IOException, InterruptedException;

        @Override
        default HttpReply send(HttpRequest request) throws IOException, InterruptedException {
            var reply = sendStreamed(request);
            try (var body = reply.body()) {
                var bytes = body == null ? new byte[0] : body.readAllBytes();
                return new HttpReply(reply.statusCode(), reply.contentType(), bytes);
            }
        }
    }

    private static final class JdkHttpExchange implements StreamedExchange {

        private final HttpAnnotationEgressPolicy policy;
        private HttpClient client;

        private JdkHttpExchange(HttpAnnotationEgressPolicy policy) {
            this.policy = policy;
        }

        @Override
        public StreamedReply sendStreamed(HttpRequest request) throws IOException, InterruptedException {
            var response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            var contentType = response.headers().firstValue("content-type").orElse(null);
            var location = response.headers().firstValue("location").orElse(null);
            return new StreamedReply(response.statusCode(), contentType, location, response.body());
        }

        private synchronized HttpClient client() {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(policy.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
            }
            return client;
        }
    }
}

package org.masymos.core.annotation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The one transport seam of the ontology term providers: one exchange, bounded the way {@link
 * HttpAnnotationResourceFetcher} bounds a page fetch.
 *
 * <p>Providers depend on this interface, never on {@link HttpClient}, so every adapter test runs
 * without a network: a transport that throws when called is the proof that a mode made no provider
 * request.
 *
 * <p>Unlike the page fetcher, this seam reports rather than swallows: a non-2xx status is returned to
 * the adapter, which decides whether it is a per-class "no such class" or a batch failure, and a
 * transport failure is thrown as {@link IllegalStateException}. The provider is demoted by the
 * resolver on a throw; that is the only demotion signal there is.
 *
 * <p>Bounds: {@link #CONNECT_TIMEOUT}, {@link #READ_TIMEOUT} and {@link
 * HttpAnnotationResourceFetcher#DEFAULT_MAX_BODY_BYTES}, the values the page fetcher enforces.
 */
@FunctionalInterface
public interface AnnotationProviderHttp {

    /** Placeholder written in place of a secret header value in any rendering. */
    String REDACTED = "<redacted>";

    /** Connect timeout of the provider transport, identical to the page fetcher's. */
    Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Read timeout of the provider transport, identical to the page fetcher's. */
    Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** Sends one request and returns its response. Never returns {@code null}. */
    ProviderResponse send(ProviderRequest request);

    /** The real transport with the page fetcher's body bound. */
    static AnnotationProviderHttp jdk() {
        return new JdkProviderHttp(HttpAnnotationResourceFetcher.DEFAULT_MAX_BODY_BYTES);
    }

    /** The real transport with an explicit body bound, for a caller that knows better. */
    static AnnotationProviderHttp jdk(int maxBodyBytes) {
        return new JdkProviderHttp(maxBodyBytes);
    }

    /** One request: method, absolute URL, headers and an optional body. */
    record ProviderRequest(String method, String url, Map<String, String> headers, String body) {

        public ProviderRequest {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(url, "url");
            method = method.trim().toUpperCase(Locale.ROOT);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        /** A request without a body, for example a {@code GET}. */
        public static ProviderRequest get(String url, Map<String, String> headers) {
            return new ProviderRequest("GET", url, headers, null);
        }

        /**
         * The same request with every listed header value replaced by {@link #REDACTED}. Used wherever
         * a request is rendered — a failure reason, a message — so a secret never leaks through a
         * rendering.
         */
        public ProviderRequest redacted(Collection<String> secretHeaders) {
            if (secretHeaders == null || secretHeaders.isEmpty()) {
                return this;
            }
            var lowered = new LinkedHashSet<String>();
            for (var header : secretHeaders) {
                if (header != null) {
                    lowered.add(header.toLowerCase(Locale.ROOT));
                }
            }
            var copy = new LinkedHashMap<String, String>();
            headers.forEach((name, value) ->
                    copy.put(name, lowered.contains(name.toLowerCase(Locale.ROOT)) ? REDACTED : value));
            return new ProviderRequest(method, url, copy, body);
        }

        /** The safe wire form of this request: method and URL, never a header value. */
        @Override
        public String toString() {
            return method + " " + url;
        }
    }

    /** One response: status, decoded body (bounded) and content type, as the server sent them. */
    record ProviderResponse(int status, String body, String contentType) {

        public ProviderResponse {
            body = body == null ? "" : body;
        }

        public boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    /** The JDK implementation. The client is created once, on first use of this instance. */
    final class JdkProviderHttp implements AnnotationProviderHttp {

        private final int maxBodyBytes;
        private HttpClient client;

        private JdkProviderHttp(int maxBodyBytes) {
            this.maxBodyBytes = Math.max(1, maxBodyBytes);
        }

        @Override
        public ProviderResponse send(ProviderRequest request) {
            Objects.requireNonNull(request, "request");
            var lower = request.url().toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new IllegalStateException("provider request rejected: unsupported scheme: " + request);
            }

            var builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(READ_TIMEOUT);
            request.headers().forEach(builder::header);
            if (request.body() == null) {
                builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
            } else if ("GET".equals(request.method())) {
                throw new IllegalStateException("provider request rejected: GET with a body: " + request);
            } else {
                builder.method(request.method(),
                        HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
            }

            HttpResponse<byte[]> response;
            try {
                response = client().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("provider request interrupted: " + request, exception);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException(
                        "provider transport failed: " + exception.getClass().getSimpleName() + ": " + request,
                        exception);
            }

            var body = response.body() == null ? new byte[0] : response.body();
            var bounded = body.length > maxBodyBytes ? Arrays.copyOf(body, maxBodyBytes) : body;
            var contentType = response.headers().firstValue("content-type").orElse(null);
            return new ProviderResponse(response.statusCode(), new String(bounded, charsetOf(contentType)), contentType);
        }

        private synchronized HttpClient client() {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            }
            return client;
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
    }
}

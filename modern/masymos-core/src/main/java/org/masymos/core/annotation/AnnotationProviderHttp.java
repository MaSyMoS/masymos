package org.masymos.core.annotation;

import java.io.IOException;
import java.io.InputStream;
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
 * <p>Bounds come from one {@link HttpAnnotationEgressPolicy} instance, the same one the page fetcher
 * consumes: connect and read timeout, the host allowlist, the streamed body cap and the one sanctioned
 * redirect hop. A host outside the allowlist is refused before a request exists, with the same reason
 * text the page fetcher reports.
 */
@FunctionalInterface
public interface AnnotationProviderHttp {

    /** Placeholder written in place of a secret header value in any rendering. */
    String REDACTED = "<redacted>";

    /** Connect timeout of the provider transport, identical to the page fetcher's. */
    Duration CONNECT_TIMEOUT = HttpAnnotationEgressPolicy.DEFAULT_CONNECT_TIMEOUT;

    /** Read timeout of the provider transport, identical to the page fetcher's. */
    Duration READ_TIMEOUT = HttpAnnotationEgressPolicy.DEFAULT_READ_TIMEOUT;

    /** Sends one request and returns its response. Never returns {@code null}. */
    ProviderResponse send(ProviderRequest request);

    /** The real transport with the shipped policy. */
    static AnnotationProviderHttp jdk() {
        return jdk(HttpAnnotationEgressPolicy.defaults());
    }

    /** The real transport with an explicit body bound, for a caller that knows better. */
    static AnnotationProviderHttp jdk(int maxBodyBytes) {
        return jdk(new HttpAnnotationEgressPolicy(
                HttpAnnotationEgressPolicy.DEFAULT_CONNECT_TIMEOUT,
                HttpAnnotationEgressPolicy.DEFAULT_READ_TIMEOUT,
                maxBodyBytes,
                HttpAnnotationEgressPolicy.DEFAULT_ALLOWED_HOSTS,
                HttpAnnotationEgressPolicy.DEFAULT_MAX_REDIRECT_HOPS));
    }

    /** The real transport consuming {@code policy}, the instance the page fetcher also consumes. */
    static AnnotationProviderHttp jdk(HttpAnnotationEgressPolicy policy) {
        return new JdkProviderHttp(policy);
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

        private final HttpAnnotationEgressPolicy policy;
        private HttpClient client;

        private JdkProviderHttp(HttpAnnotationEgressPolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        @Override
        public ProviderResponse send(ProviderRequest request) {
            Objects.requireNonNull(request, "request");
            var lower = request.url().toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new IllegalStateException("provider request rejected: unsupported scheme: " + request);
            }

            var target = URI.create(request.url());
            var hops = 0;
            while (true) {
                var refusal = policy.refusal(target);
                if (refusal.isPresent()) {
                    throw new IllegalStateException("provider request rejected: " + refusal.get() + ": " + request);
                }

                var builder = HttpRequest.newBuilder(target).timeout(policy.readTimeout());
                request.headers().forEach(builder::header);
                if (request.body() == null) {
                    builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
                } else if ("GET".equals(request.method())) {
                    throw new IllegalStateException("provider request rejected: GET with a body: " + request);
                } else {
                    builder.method(request.method(),
                            HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
                }

                HttpResponse<InputStream> response;
                try {
                    response = client().send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("provider request interrupted: " + request, exception);
                } catch (IOException | RuntimeException exception) {
                    throw new IllegalStateException(
                            "provider transport failed: " + exception.getClass().getSimpleName() + ": " + request,
                            exception);
                }

                var contentType = response.headers().firstValue("content-type").orElse(null);
                if (isRedirect(response.statusCode())) {
                    var location = response.headers().firstValue("location").orElse(null);
                    closeQuietly(response.body());
                    if (location == null) {
                        return new ProviderResponse(response.statusCode(), "", contentType);
                    }
                    if (hops >= policy.maxRedirectHops()) {
                        throw new IllegalStateException(
                                "provider request rejected: redirect not followed: " + location + ": " + request);
                    }
                    var next = target.resolve(location);
                    hops++;
                    target = next;
                    continue;
                }

                byte[] bounded;
                try {
                    bounded = readBounded(response.body());
                } catch (IOException exception) {
                    throw new IllegalStateException(
                            "provider transport failed: " + exception.getClass().getSimpleName() + ": " + request,
                            exception);
                }
                return new ProviderResponse(response.statusCode(), new String(bounded, charsetOf(contentType)), contentType);
            }
        }

        /** Reads at most the policy's cap and abandons the rest instead of materialising it. */
        private byte[] readBounded(InputStream stream) throws IOException {
            var limit = policy.maxBodyBytes();
            var buffer = new byte[limit];
            var filled = 0;
            try (var body = stream) {
                if (body != null) {
                    while (filled < limit) {
                        var read = body.read(buffer, filled, limit - filled);
                        if (read < 0) {
                            break;
                        }
                        filled += read;
                    }
                }
            }
            return Arrays.copyOf(buffer, filled);
        }

        private static boolean isRedirect(int statusCode) {
            return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307
                    || statusCode == 308;
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

        private synchronized HttpClient client() {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .connectTimeout(policy.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
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

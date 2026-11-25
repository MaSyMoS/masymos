package org.masymos.core.annotation;

import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One egress policy for every annotation HTTP call: connect and read timeouts, the streamed body cap,
 * the host allowlist and the redirect rule.
 *
 * <p>Both real transports consume one instance of this record — the page fetch of {@link
 * HttpAnnotationResourceFetcher} and the provider seam of {@link
 * AnnotationProviderHttp#jdk(HttpAnnotationEgressPolicy)} — so a page and a provider call cannot
 * disagree about which hosts are reachable or how much of a body they will read.
 *
 * <p>The redirect rule is {@code HttpClient.Redirect.NEVER} plus at most one sanctioned hop: the
 * client follows nothing, and the transport follows a single {@code Location} target itself, only when
 * that target is on the allowlist. A refused host is reported as {@code host not allowed: <host>},
 * which callers record as {@code TRANSIENT} rather than as a definitive negative.
 *
 * <p>An allowlist entry is either a bare host ({@code identifiers.org}, any port) or {@code
 * host:port}; matching is case-insensitive. The defaults are the four hosts measured in {@code
 * docs/modernization/sources-and-fixtures.md}: {@code identifiers.org} and the three redirect targets
 * the six namespaces resolve to. The allowlist is configurable through the property {@value
 * #ALLOWED_HOSTS_PROPERTY} (comma separated).
 */
public record HttpAnnotationEgressPolicy(
        Duration connectTimeout,
        Duration readTimeout,
        int maxBodyBytes,
        Set<String> allowedHosts,
        int maxRedirectHops) {

    /** The body cap both transports apply, in bytes. */
    public static final int DEFAULT_MAX_BODY_BYTES = 512 * 1024;

    /** Connect timeout of both transports. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Read timeout of both transports. */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    /** One sanctioned redirect hop: the client follows none, the transport at most this many. */
    public static final int DEFAULT_MAX_REDIRECT_HOPS = 1;

    /** Property naming the comma-separated allowlist. */
    public static final String ALLOWED_HOSTS_PROPERTY = "masymos.annotations.fetch.allowed-hosts";

    /** The hosts the shipped catalogue pages and their redirects reach (decision D1, 2026-11-21). */
    public static final Set<String> DEFAULT_ALLOWED_HOSTS = Set.of(
            "identifiers.org",
            "purl.bioontology.org",
            "registry.identifiers.org",
            "www.ebi.ac.uk");

    public HttpAnnotationEgressPolicy {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive: " + connectTimeout);
        }
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout must be positive: " + readTimeout);
        }
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("maxBodyBytes must be at least 1: " + maxBodyBytes);
        }
        if (maxRedirectHops < 0 || maxRedirectHops > 1) {
            throw new IllegalArgumentException("at most one sanctioned redirect hop: " + maxRedirectHops);
        }
        var hosts = new LinkedHashSet<String>();
        for (var host : allowedHosts == null ? Set.<String>of() : allowedHosts) {
            if (host == null || host.isBlank()) {
                continue;
            }
            hosts.add(host.trim().toLowerCase(Locale.ROOT));
        }
        allowedHosts = Set.copyOf(hosts);
    }

    /** The shipped defaults: default timeouts, the default body cap, the measured allowlist. */
    public static HttpAnnotationEgressPolicy defaults() {
        return new HttpAnnotationEgressPolicy(
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_READ_TIMEOUT,
                DEFAULT_MAX_BODY_BYTES,
                DEFAULT_ALLOWED_HOSTS,
                DEFAULT_MAX_REDIRECT_HOPS);
    }

    /** The defaults with an explicit allowlist, for a caller that knows its hosts. */
    public static HttpAnnotationEgressPolicy withAllowedHosts(Collection<String> hosts) {
        return new HttpAnnotationEgressPolicy(
                DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_READ_TIMEOUT,
                DEFAULT_MAX_BODY_BYTES,
                hosts == null ? Set.of() : Set.copyOf(hosts),
                DEFAULT_MAX_REDIRECT_HOPS);
    }

    /** Whether {@code uri}'s host (and port, when an entry names one) is on the allowlist. */
    public boolean allows(URI uri) {
        return refusal(uri).isEmpty();
    }

    /**
     * Why {@code uri} may not be contacted, or an empty optional when it may. The text is the one both
     * transports report, so a caller sees the same reason from a page fetch and from a provider call.
     */
    public Optional<String> refusal(URI uri) {
        var host = uri == null ? null : uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.of("host not allowed: <no host>");
        }
        var normalised = host.toLowerCase(Locale.ROOT);
        if (allowedHosts.contains(normalised)) {
            return Optional.empty();
        }
        var port = uri.getPort();
        if (port > 0 && allowedHosts.contains(normalised + ":" + port)) {
            return Optional.empty();
        }
        return Optional.of("host not allowed: " + normalised);
    }
}

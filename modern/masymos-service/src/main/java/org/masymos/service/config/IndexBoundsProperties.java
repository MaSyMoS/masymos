package org.masymos.service.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How large one index request may be (task T-018, finding GAP02-002).
 *
 * <p>Legacy MaSyMoS read a directory and had no request at all; the modern surface takes a list of
 * source URIs in one body, and without a bound one call can ask for any number of documents to be
 * fetched, parsed and written. The cap is applied at the request boundary, before the first document is
 * written, so an over-cap request is refused whole rather than partially executed.
 *
 * <p>Two properties, two resources. {@code max-sources-per-request} bounds the request itself. {@code
 * max-response-bytes} is the ceiling for a response body a caller dumps to disk - the CLI's {@code
 * --dump-path}; the service does not enforce it, because what it answers is already bounded by the
 * source cap, and it is declared here so both halves of the surface state the bound in the same place.
 */
@ConfigurationProperties(prefix = "masymos.index")
public record IndexBoundsProperties(Integer maxSourcesPerRequest, Long maxResponseBytes) {
    /** 100 documents per request keeps one HTTP call within a bounded parse-and-write cost. */
    public static final int DEFAULT_MAX_SOURCES_PER_REQUEST = 100;

    /** 50 MiB, five times one document ceiling, so one large but legitimate batch still fits. */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 52_428_800L;

    public int maxSourcesPerRequestOrDefault() {
        return maxSourcesPerRequest == null ? DEFAULT_MAX_SOURCES_PER_REQUEST : maxSourcesPerRequest;
    }

    public long maxResponseBytesOrDefault() {
        return maxResponseBytes == null ? DEFAULT_MAX_RESPONSE_BYTES : maxResponseBytes;
    }

    /**
     * Refuses a source list longer than {@link #maxSourcesPerRequestOrDefault()}.
     *
     * @throws IllegalArgumentException when the list is over the cap; {@code ApiExceptionHandler}
     *     renders it as the documented {@code 400 invalid-argument}, and nothing has been written yet
     */
    public void requireWithinSourceCap(List<?> sources) {
        var cap = maxSourcesPerRequestOrDefault();
        if (cap < 1) {
            // A cap of zero would refuse every request, which is never what an operator meant.
            throw new IllegalArgumentException(
                    "masymos.index.max-sources-per-request must be at least 1, was " + cap);
        }
        if (sources.size() > cap) {
            throw new IllegalArgumentException("at most " + cap
                    + " sources may be indexed per request, was " + sources.size()
                    + " (masymos.index.max-sources-per-request)");
        }
    }

    public static IndexBoundsProperties defaults() {
        return new IndexBoundsProperties(DEFAULT_MAX_SOURCES_PER_REQUEST, DEFAULT_MAX_RESPONSE_BYTES);
    }
}

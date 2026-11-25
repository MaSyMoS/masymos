package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.masymos.core.config.AnnotationServiceBounds;

class AnnotationEnrichmentServiceTest {

    private final AnnotationTextExtractor extractor = new AnnotationTextExtractor();

    @Test
    void indexesResolvableResourceWithSearchableText() {
        var service = new AnnotationEnrichmentService(
                new AnnotationUriResolver(),
                url -> new AnnotationFetchResult(url, 200, "text/html", "<h1>Apoptosis</h1>", null),
                extractor
        );

        var outcomes = service.enrich(AnnotationEnrichmentRequest.of(List.of("https://example.org/term")));

        assertEquals(1, outcomes.size());
        assertEquals(AnnotationEnrichmentOutcome.Status.INDEXED, outcomes.getFirst().status());
        assertEquals("Apoptosis", outcomes.getFirst().searchableText());
        assertEquals("https://example.org/term", outcomes.getFirst().resourceUri());
        assertTrue(outcomes.getFirst().indexed());
    }

    @Test
    void skipsResourceWhenEveryCandidateFails() {
        var service = new AnnotationEnrichmentService(
                new AnnotationUriResolver(),
                url -> AnnotationFetchResult.failure(url, "http status 503"),
                extractor
        );

        var outcomes = service.enrich(AnnotationEnrichmentRequest.of(List.of("https://example.org/term")));

        assertEquals(AnnotationEnrichmentOutcome.Status.SKIPPED, outcomes.getFirst().status());
        assertEquals("http status 503", outcomes.getFirst().failureReason());
        assertEquals("", outcomes.getFirst().searchableText());
    }

    @Test
    void skipsUnresolvableResourceWithoutFetching() {
        var calls = new AtomicInteger();
        var service = new AnnotationEnrichmentService(
                new AnnotationUriResolver(),
                url -> {
                    calls.incrementAndGet();
                    return new AnnotationFetchResult(url, 200, "text/plain", "text", null);
                },
                extractor
        );

        var outcomes = service.enrich(AnnotationEnrichmentRequest.of(List.of("urn:example:nothing")));

        assertEquals(AnnotationEnrichmentOutcome.Status.SKIPPED, outcomes.getFirst().status());
        assertEquals("unresolved annotation uri", outcomes.getFirst().failureReason());
        assertEquals(0, calls.get());
    }

    @Test
    void honoursCandidateLimit() {
        var calls = new AtomicInteger();
        var service = new AnnotationEnrichmentService(
                new AnnotationUriResolver(),
                url -> {
                    calls.incrementAndGet();
                    return AnnotationFetchResult.failure(url, "http status 500");
                },
                extractor
        );
        var request = new AnnotationEnrichmentRequest(List.of("http://identifiers.org/pubmed/11429446"), 2, 1, 1000);

        var outcomes = service.enrich(request);

        assertEquals(1, calls.get());
        assertEquals(1, outcomes.getFirst().candidateUrls().size());
    }

    @Test
    void deduplicatesAndSkipsBlankResourceUris() {
        var service = new AnnotationEnrichmentService(
                new AnnotationUriResolver(),
                url -> new AnnotationFetchResult(url, 200, "text/plain", "text", null),
                extractor
        );

        var outcomes = service.enrich(AnnotationEnrichmentRequest.of(
                List.of("https://example.org/a", "https://example.org/a", "  ", "https://example.org/b")
        ));

        assertEquals(2, outcomes.size());
    }

    @Test
    void isolatesThrowingFetcher() {
        var service = new AnnotationEnrichmentService(
                new AnnotationUriResolver(),
                url -> {
                    throw new IllegalStateException("boom");
                },
                extractor
        );

        var outcomes = service.enrich(AnnotationEnrichmentRequest.of(List.of("https://example.org/a")));

        assertEquals(AnnotationEnrichmentOutcome.Status.FAILED, outcomes.getFirst().status());
        assertTrue(outcomes.getFirst().failureReason().contains("boom"), outcomes.getFirst().failureReason());
    }

    @Test
    void truncatesToRequestBound() {
        var service = new AnnotationEnrichmentService(
                new AnnotationUriResolver(),
                url -> new AnnotationFetchResult(url, 200, "text/plain", "abcdefghij", null),
                extractor
        );
        var request = new AnnotationEnrichmentRequest(List.of("https://example.org/a"), 1, 1, 4);

        assertEquals("abcd", service.enrich(request).getFirst().searchableText());
    }

    /**
     * The egress allowlist is a pre-submission gate, not only a fetcher internal: a resource URI on a
     * host the policy refuses is never submitted, so the fetcher is not reached at all and the outcome
     * carries the policy's own refusal text. The allowlisted resource in the same request is still
     * processed, so a service that refuses everything cannot pass.
     */
    @Test
    void aNonAllowlistedHostIsRefusedThroughTheEnrichmentService() {
        var policy = HttpAnnotationEgressPolicy.withAllowedHosts(Set.of("allowed.example"));
        var allowedUrl = "https://allowed.example/term/2";
        var calls = new AtomicInteger();
        var fetchedUrls = ConcurrentHashMap.<String>newKeySet();
        AnnotationResourceFetcher countingFetcher = url -> {
            calls.incrementAndGet();
            fetchedUrls.add(url);
            return new AnnotationFetchResult(url, 200, "text/plain", "term text", null);
        };
        var service = new AnnotationEnrichmentService(
                new AnnotationServiceBounds(policy, 1_000, 1_000),
                new AnnotationUriResolver(),
                countingFetcher,
                extractor
        );

        var outcomes = service.enrich(AnnotationEnrichmentRequest.of(
                List.of("https://not-allowed.example/term/1", allowedUrl)));

        assertEquals(2, outcomes.size());
        var refused = outcomes.stream()
                .filter(outcome -> "https://not-allowed.example/term/1".equals(outcome.resourceUri()))
                .findFirst()
                .orElseThrow();
        var allowed = outcomes.stream()
                .filter(outcome -> allowedUrl.equals(outcome.resourceUri()))
                .findFirst()
                .orElseThrow();

        assertFalse(fetchedUrls.contains("https://not-allowed.example/term/1"), "no fetch for the refused host");
        assertEquals(AnnotationEnrichmentOutcome.Status.SKIPPED, refused.status());
        assertEquals(
                policy.refusal(URI.create("https://not-allowed.example/term/1")).orElseThrow(),
                refused.failureReason());
        assertEquals("", refused.searchableText());
        assertEquals(AnnotationEnrichmentOutcome.Status.INDEXED, allowed.status(), "the allowlisted resource is still processed");
        assertEquals(1, calls.get(), "exactly the allowlisted resource reached the fetcher");
        assertEquals(Set.of(allowedUrl), Set.copyOf(fetchedUrls));
    }

    @Test
    void returnsEmptyListForNoInput() {
        var service = new AnnotationEnrichmentService();

        assertTrue(service.enrich(AnnotationEnrichmentRequest.of(List.of())).isEmpty());
        assertFalse(service.enrich(AnnotationEnrichmentRequest.of(null)).iterator().hasNext());
    }

    /**
     * {@code REL-005}: the run holds at most {@code maxConcurrency} tasks, so a hundred resources do
     * not become a hundred queued jobs. The four first tasks block until all four of them are inside
     * the fetcher, which is what makes the measured maximum *exactly* four rather than "up to four":
     * an executor that admits more than the requested concurrency shows it here.
     */
    @Test
    void neverHoldsMoreTasksThanTheConcurrency() throws Exception {
        var maxConcurrency = 4;
        var resources = IntStream.range(0, 100)
                .mapToObj(index -> "https://example.org/term/" + index)
                .toList();
        var running = new AtomicInteger();
        var maxRunning = new AtomicInteger();
        var firstWindowInside = new CountDownLatch(maxConcurrency);
        AnnotationResourceFetcher fetcher = url -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                firstWindowInside.countDown();
                firstWindowInside.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return new AnnotationFetchResult(url, 200, "text/plain", "text", null);
        };
        var service = new AnnotationEnrichmentService(new AnnotationUriResolver(), fetcher, extractor);

        var outcomes = service.enrich(new AnnotationEnrichmentRequest(resources, maxConcurrency, 3, 1000));

        assertEquals(maxConcurrency, maxRunning.get(), "at most maxConcurrency tasks may run at once");
        assertEquals(100, outcomes.size());
        assertTrue(outcomes.stream().allMatch(AnnotationEnrichmentOutcome::indexed), "every resource is processed");
    }

    /**
     * {@code REL-005}: an interrupted run says so. The queued resources are cancelled rather than
     * reported as "not processed" failures - the caller asked the run to stop, nothing was broken.
     */
    @Test
    void anInterruptCancelsQueuedTasksAndReportsThemAsInterrupted() throws Exception {
        var maxConcurrency = 4;
        var resources = IntStream.range(0, 100)
                .mapToObj(index -> "https://example.org/term/" + index)
                .toList();
        var started = ConcurrentHashMap.<String>newKeySet();
        var firstWindowInside = new CountDownLatch(maxConcurrency);
        AnnotationResourceFetcher fetcher = url -> {
            started.add(url);
            firstWindowInside.countDown();
            try {
                // Held until the caller is interrupted: no window after the first one can start.
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new AnnotationFetchResult(url, 200, "text/plain", "text", null);
        };
        var service = new AnnotationEnrichmentService(new AnnotationUriResolver(), fetcher, extractor);
        var request = new AnnotationEnrichmentRequest(resources, maxConcurrency, 3, 1000);
        var outcomes = new AtomicReference<List<AnnotationEnrichmentOutcome>>();
        var thrown = new AtomicReference<Throwable>();
        var caller = new Thread(() -> {
            try {
                outcomes.set(service.enrich(request));
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, "enrichment-caller");

        caller.start();
        assertTrue(firstWindowInside.await(10, TimeUnit.SECONDS), "the first window must reach the fetcher");
        caller.interrupt();
        caller.join(10_000);
        assertFalse(caller.isAlive(), "enrich must return once its caller is interrupted");
        assertNull(thrown.get(), "enrich never throws");
        var results = outcomes.get();
        assertNotNull(results);

        assertEquals(100, results.size());
        Set<String> queued = results.stream()
                .map(AnnotationEnrichmentOutcome::resourceUri)
                .filter(uri -> !started.contains(uri))
                .collect(Collectors.toSet());
        assertFalse(queued.isEmpty(), "the interrupt must leave part of the request unprocessed");
        for (var outcome : results) {
            if (!queued.contains(outcome.resourceUri())) {
                continue;
            }
            assertEquals(AnnotationEnrichmentOutcome.Status.SKIPPED, outcome.status(), outcome.resourceUri());
            assertEquals("interrupted", outcome.failureReason(), outcome.resourceUri());
        }
        assertTrue(started.size() <= 2 * maxConcurrency, "a cancelled run starts no further window: " + started.size());
    }
}

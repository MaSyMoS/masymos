package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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

    @Test
    void returnsEmptyListForNoInput() {
        var service = new AnnotationEnrichmentService();

        assertTrue(service.enrich(AnnotationEnrichmentRequest.of(List.of())).isEmpty());
        assertFalse(service.enrich(AnnotationEnrichmentRequest.of(null)).iterator().hasNext());
    }
}

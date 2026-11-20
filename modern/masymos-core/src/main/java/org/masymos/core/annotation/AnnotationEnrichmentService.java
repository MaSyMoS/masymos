package org.masymos.core.annotation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Storage-neutral annotation enrichment pipeline: resolve URI, fetch candidates with bounded
 * concurrency, extract searchable text.
 *
 * <p>This class never talks to Neo4j and never throws. Persisting the result is the storage layer's
 * job; {@link AnnotationEnrichmentOutcome} carries everything it needs.
 */
public final class AnnotationEnrichmentService {

    private final AnnotationUriResolver uriResolver;
    private final AnnotationResourceFetcher fetcher;
    private final AnnotationTextExtractor textExtractor;

    public AnnotationEnrichmentService() {
        this(new AnnotationUriResolver(), new HttpAnnotationResourceFetcher(), new AnnotationTextExtractor());
    }

    public AnnotationEnrichmentService(
            AnnotationUriResolver uriResolver,
            AnnotationResourceFetcher fetcher,
            AnnotationTextExtractor textExtractor
    ) {
        this.uriResolver = uriResolver;
        this.fetcher = fetcher;
        this.textExtractor = textExtractor;
    }

    public List<AnnotationEnrichmentOutcome> enrich(AnnotationEnrichmentRequest request) {
        var uris = distinctResourceUris(request.resourceUris());
        if (uris.isEmpty()) {
            return List.of();
        }

        var outcomes = new AtomicReferenceArray<AnnotationEnrichmentOutcome>(uris.size());
        var concurrency = Math.max(1, Math.min(request.maxConcurrency(), uris.size()));
        var executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            var thread = new Thread(runnable, "annotation-enrichment");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var futures = new ArrayList<Future<?>>();
            for (var index = 0; index < uris.size(); index++) {
                var position = index;
                var resourceUri = uris.get(index);
                futures.add(executor.submit(() -> outcomes.set(position, enrichSingle(resourceUri, request))));
            }
            for (var future : futures) {
                try {
                    future.get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException exception) {
                    // enrichSingle never throws; an ExecutionException here is an executor artefact.
                }
            }
        } finally {
            executor.shutdownNow();
        }

        var results = new ArrayList<AnnotationEnrichmentOutcome>(uris.size());
        for (var index = 0; index < uris.size(); index++) {
            var outcome = outcomes.get(index);
            results.add(outcome == null
                    ? new AnnotationEnrichmentOutcome(uris.get(index), AnnotationEnrichmentOutcome.Status.FAILED, List.of(), "", "not processed")
                    : outcome);
        }
        return List.copyOf(results);
    }

    private AnnotationEnrichmentOutcome enrichSingle(String resourceUri, AnnotationEnrichmentRequest request) {
        try {
            var resolution = uriResolver.resolve(resourceUri);
            if (!resolution.resolvable()) {
                return new AnnotationEnrichmentOutcome(
                        resourceUri,
                        AnnotationEnrichmentOutcome.Status.SKIPPED,
                        List.of(),
                        "",
                        "unresolved annotation uri"
                );
            }

            var candidates = resolution.candidateUrls().stream()
                    .limit(request.maxCandidatesPerResource())
                    .toList();
            String lastFailure = null;
            for (var candidate : candidates) {
                var fetched = fetcher.fetch(candidate);
                if (!fetched.succeeded()) {
                    lastFailure = fetched.failureReason();
                    continue;
                }
                var text = truncate(textExtractor.extract(fetched.contentType(), fetched.body()), request.maxTextLength());
                if (text.isBlank()) {
                    lastFailure = "no extractable text";
                    continue;
                }
                return new AnnotationEnrichmentOutcome(
                        resourceUri,
                        AnnotationEnrichmentOutcome.Status.INDEXED,
                        candidates,
                        text,
                        null
                );
            }

            return new AnnotationEnrichmentOutcome(
                    resourceUri,
                    AnnotationEnrichmentOutcome.Status.SKIPPED,
                    candidates,
                    "",
                    lastFailure
            );
        } catch (RuntimeException exception) {
            return new AnnotationEnrichmentOutcome(
                    resourceUri,
                    AnnotationEnrichmentOutcome.Status.FAILED,
                    List.of(),
                    "",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    private static List<String> distinctResourceUris(List<String> resourceUris) {
        var distinct = new LinkedHashSet<String>();
        for (var uri : resourceUris) {
            if (uri != null && !uri.isBlank()) {
                distinct.add(uri.trim());
            }
        }
        return List.copyOf(distinct);
    }

    private static String truncate(String text, int maxTextLength) {
        return text.length() <= maxTextLength ? text : text.substring(0, maxTextLength);
    }
}

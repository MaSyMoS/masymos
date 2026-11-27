package org.masymos.core.annotation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.masymos.core.config.AnnotationServiceBounds;

/**
 * Storage-neutral annotation enrichment pipeline: resolve URI, fetch candidates with bounded
 * concurrency, extract searchable text.
 *
 * <p>This class never talks to Neo4j and never throws. Persisting the result is the storage layer's
 * job; {@link AnnotationEnrichmentOutcome} carries everything it needs.
 */
public final class AnnotationEnrichmentService {

    /** Reason for a resource the run never reached because its caller was interrupted. */
    private static final String INTERRUPTED_REASON = "interrupted";

    /** Reason for a resource no task produced an outcome for, other than by interruption. */
    private static final String NOT_PROCESSED_REASON = "not processed";

    private final AnnotationUriResolver uriResolver;
    private final AnnotationResourceFetcher fetcher;
    private final AnnotationTextExtractor textExtractor;
    private final HttpAnnotationEgressPolicy policy;

    public AnnotationEnrichmentService() {
        this(AnnotationServiceBounds.defaults());
    }

    /**
     * The enrichment run over one egress policy instance: the fetcher that reads a resource URI applies
     * the same allowlist, body cap and redirect rule as every other annotation call, and a resource URI
     * the policy refuses is never submitted, so no fetch is attempted for it.
     */
    public AnnotationEnrichmentService(HttpAnnotationEgressPolicy policy) {
        this(policy, new AnnotationUriResolver(), new HttpAnnotationResourceFetcher(policy), new AnnotationTextExtractor());
    }

    /**
     * The enrichment run over the service bounds, the one injection point the annotation service takes:
     * the fetcher is built from {@link AnnotationServiceBounds#egress()}, and that same policy decides
     * which resource URIs are submitted at all.
     */
    public AnnotationEnrichmentService(AnnotationServiceBounds bounds) {
        this(bounds.egress(), new AnnotationUriResolver(), new HttpAnnotationResourceFetcher(bounds.egress()), new AnnotationTextExtractor());
    }

    public AnnotationEnrichmentService(
            AnnotationUriResolver uriResolver,
            AnnotationResourceFetcher fetcher,
            AnnotationTextExtractor textExtractor
    ) {
        this((HttpAnnotationEgressPolicy) null, uriResolver, fetcher, textExtractor);
    }

    /** The bounds' egress policy decides submission; the given fetcher is what a submitted resource reaches. */
    public AnnotationEnrichmentService(
            AnnotationServiceBounds bounds,
            AnnotationUriResolver uriResolver,
            AnnotationResourceFetcher fetcher,
            AnnotationTextExtractor textExtractor
    ) {
        this(bounds.egress(), uriResolver, fetcher, textExtractor);
    }

    private AnnotationEnrichmentService(
            HttpAnnotationEgressPolicy policy,
            AnnotationUriResolver uriResolver,
            AnnotationResourceFetcher fetcher,
            AnnotationTextExtractor textExtractor
    ) {
        this.policy = policy;
        this.uriResolver = uriResolver;
        this.fetcher = fetcher;
        this.textExtractor = textExtractor;
    }

    /**
     * Runs the enrichment pipeline over the request's distinct resource URIs.
     *
     * <p>The submission queue is bounded ({@code REL-005}): the resources are submitted in windows of
     * {@code request.maxConcurrency()} and each window is drained before the next one is submitted,
     * so a request over a thousand resources holds a fixed number of live tasks instead of one task
     * and one future per resource.
     *
     * <p>An interrupted caller stops the run. The window in flight is cancelled, no further window is
     * submitted and the interrupt status is restored, so the caller learns the run was cut short. Every
     * resource without an outcome is then reported {@link AnnotationEnrichmentOutcome.Status#SKIPPED}
     * with the reason {@code interrupted}; {@link AnnotationEnrichmentOutcome.Status#FAILED} with
     * {@code not processed} stays what it was - a task that vanished for any other cause.
     *
     * <p>A resource URI the egress policy refuses is <em>not submitted</em>: its outcome is recorded
     * before the executor is touched, carrying the policy's own refusal text, so the run never opens a
     * connection for a host it may not contact. The remaining resources are submitted as usual.
     */
    public List<AnnotationEnrichmentOutcome> enrich(AnnotationEnrichmentRequest request) {
        var uris = distinctResourceUris(request.resourceUris());
        if (uris.isEmpty()) {
            return List.of();
        }

        var outcomes = new AtomicReferenceArray<AnnotationEnrichmentOutcome>(uris.size());
        var refused = new LinkedHashSet<String>();
        if (policy != null) {
            for (var index = 0; index < uris.size(); index++) {
                var refusal = policy.refusal(toUri(uris.get(index)));
                if (refusal.isPresent()) {
                    outcomes.set(index, new AnnotationEnrichmentOutcome(
                            uris.get(index),
                            AnnotationEnrichmentOutcome.Status.SKIPPED,
                            List.of(),
                            "",
                            refusal.get()));
                    refused.add(uris.get(index));
                }
            }
        }
        var concurrency = Math.max(1, Math.min(request.maxConcurrency(), uris.size()));
        var executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            var thread = new Thread(runnable, "annotation-enrichment");
            thread.setDaemon(true);
            return thread;
        });
        var interrupted = false;
        try {
            for (var start = 0; start < uris.size() && !interrupted; start += concurrency) {
                var end = Math.min(start + concurrency, uris.size());
                var window = new ArrayList<Future<?>>(end - start);
                for (var index = start; index < end; index++) {
                    var position = index;
                    var resourceUri = uris.get(index);
                    if (refused.contains(resourceUri)) {
                        continue;
                    }
                    window.add(executor.submit(() -> outcomes.set(position, enrichSingle(resourceUri, request))));
                }
                for (var future : window) {
                    try {
                        future.get();
                    } catch (InterruptedException exception) {
                        interrupted = true;
                        Thread.currentThread().interrupt();
                        window.forEach(pending -> pending.cancel(true));
                        break;
                    } catch (ExecutionException exception) {
                        // enrichSingle never throws; an ExecutionException here is an executor artefact.
                    }
                }
            }
        } finally {
            executor.shutdownNow();
        }

        var results = new ArrayList<AnnotationEnrichmentOutcome>(uris.size());
        for (var index = 0; index < uris.size(); index++) {
            var outcome = outcomes.get(index);
            if (outcome == null) {
                outcome = interrupted
                        ? new AnnotationEnrichmentOutcome(uris.get(index), AnnotationEnrichmentOutcome.Status.SKIPPED, List.of(), "", INTERRUPTED_REASON)
                        : new AnnotationEnrichmentOutcome(uris.get(index), AnnotationEnrichmentOutcome.Status.FAILED, List.of(), "", NOT_PROCESSED_REASON);
            }
            results.add(outcome);
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

    /**
     * Parses a resource URI for the policy check. Refusal of a URI that does not parse is the resolver's
     * business (it reports the resource unresolved), so this returns {@code null} and the policy names
     * the missing host.
     */
    private static URI toUri(String resourceUri) {
        try {
            return new URI(resourceUri);
        } catch (URISyntaxException exception) {
            return null;
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

package org.masymos.neo4j;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.masymos.core.annotation.AnnotationEnrichmentOutcome;
import org.masymos.core.annotation.AnnotationEnrichmentRequest;
import org.masymos.core.annotation.AnnotationEnrichmentService;
import org.masymos.core.annotation.AnnotationPrefixCatalogue;
import org.masymos.core.annotation.AnnotationTermResolver;
import org.masymos.core.annotation.AnnotationTextExtractor;
import org.masymos.core.annotation.HttpAnnotationEgressPolicy;
import org.masymos.core.annotation.HttpAnnotationResourceFetcher;
import org.masymos.core.annotation.ResolverMode;
import org.masymos.core.annotation.TermDefinition;
import org.masymos.core.annotation.TermKey;
import org.masymos.core.model.AnnotationIndexingOptions;
import org.masymos.core.model.AnnotationIndexingResult;
import org.masymos.core.spi.AnnotationIndexService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

/**
 * Persists annotation enrichment results produced by the storage-neutral term stage
 * ({@link AnnotationTermResolver}) and resource stage ({@link AnnotationEnrichmentService}).
 *
 * <p>One run resolves its distinct terms once, before the per-resource loop: every pending resource
 * URI is mapped through the {@link AnnotationPrefixCatalogue}, the distinct ontological keys are
 * resolved in the run's {@link ResolverMode} (honouring {@code refreshTerms}), and the resolution is
 * then read per resource. An ontological resource takes its text from that resolution; a
 * non-ontological resource keeps the resource-path enrichment of {@link AnnotationEnrichmentService},
 * except in {@code off}, where nothing could restore it and it is skipped instead.
 *
 * <p>Every resource the run touches records its provenance: {@code resolutionMode} always,
 * {@code resolvedVia} whenever text was written (including resource-path text, which is
 * {@code page}), and {@code termKey} exactly when the resource's URI mapped to an ontological term.
 * Provenance never outlives the state it describes: the drop and skip paths remove all three with
 * {@code resourceText}.
 *
 * <p>Fetched page text is written as {@code resource.resourceText} and propagated to the owning model
 * only as index-only enrichment. The original {@code resource.uri} stays the durable identity.
 * Runs are bounded by {@link AnnotationIndexingOptions}: {@code limit}, {@code retryFailed}, and
 * {@code maxConcurrency}.
 */
public final class Neo4jAnnotationIndexService implements AnnotationIndexService {

    private static final int MAX_CANDIDATES_PER_RESOURCE = 3;

    /** Why an {@code off} run leaves a non-ontological resource untouched: nothing could restore it. */
    static final String OFF_MODE_NON_ONTOLOGICAL_REASON = "off mode: no term and no cache";

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final AnnotationEnrichmentService enrichmentService;
    private final AnnotationPrefixCatalogue catalogue;
    private final AnnotationTermResolver resolver;

    /**
     * A resolver over {@link Neo4jAnnotationTermCache} and the existing fetcher/extractor, with no
     * provider: exactly the behaviour this service had before the term stage existed.
     */
    public Neo4jAnnotationIndexService(Driver driver, String database) {
        this(driver, database, HttpAnnotationEgressPolicy.defaults());
    }

    /**
     * The legacy wiring over one egress policy instance: the page fetcher and the enrichment fetcher are
     * built from the same policy, so a host refused in one is refused in the other.
     */
    public Neo4jAnnotationIndexService(Driver driver, String database, HttpAnnotationEgressPolicy policy) {
        this(driver, database, new AnnotationEnrichmentService(policy), policy);
    }

    /** A run configured with its providers: every run then resolves through this resolver. */
    public Neo4jAnnotationIndexService(
            Driver driver,
            String database,
            AnnotationTermResolver resolver,
            AnnotationPrefixCatalogue catalogue
    ) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
        this.enrichmentService = new AnnotationEnrichmentService();
        this.catalogue = catalogue == null ? AnnotationPrefixCatalogue.standard() : catalogue;
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    Neo4jAnnotationIndexService(Driver driver, String database, AnnotationEnrichmentService enrichmentService) {
        this(driver, database, enrichmentService, HttpAnnotationEgressPolicy.defaults());
    }

    Neo4jAnnotationIndexService(
            Driver driver,
            String database,
            AnnotationEnrichmentService enrichmentService,
            HttpAnnotationEgressPolicy policy
    ) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
        this.enrichmentService = enrichmentService;
        this.catalogue = AnnotationPrefixCatalogue.standard();
        // The legacy wiring: the Neo4j cache and the HTTP fetcher, but no provider, so every term
        // reaches the catalogue's canonical page exactly as it did before the term stage existed.
        this.resolver = new AnnotationTermResolver(
                catalogue,
                List.of(),
                new Neo4jAnnotationTermCache(driver, database),
                Clock.systemUTC(),
                AnnotationTermResolver.DEFAULT_TTL,
                new HttpAnnotationResourceFetcher(policy),
                new AnnotationTextExtractor(),
                AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
    }

    @Override
    public AnnotationIndexingResult rebuild(AnnotationIndexingOptions options) {
        var mode = options.resolverMode() == null ? ResolverMode.DIRECT : options.resolverMode();
        try (var session = driver.session(sessionConfig)) {
            if (options.dropExistingIndex()) {
                dropPreviousEnrichment(session);
            }

            var resources = pendingResources(session, options);
            if (resources.isEmpty()) {
                var empty = resolver.resolve(List.of(), options.resolverMode(), options.refreshTerms());
                return new AnnotationIndexingResult(0L, 0L, 0L, 0L, empty.summary());
            }

            // One catalogue pass for the whole run: the resource's term key, or null when its URI is
            // not an ontological term and therefore belongs to the resource path.
            var keysByResource = new LinkedHashMap<String, TermKey>();
            var distinctKeys = new LinkedHashSet<TermKey>();
            var resourcePathUris = new ArrayList<String>();
            for (var resource : resources) {
                var key = termKeyOf(resource);
                keysByResource.put(resource.getKey(), key);
                if (key == null) {
                    resourcePathUris.add(resource.getValue());
                } else {
                    distinctKeys.add(key);
                }
            }

            // The term stage runs once, before the loop: one cache read, one provider stage, one write.
            var resolution = resolver.resolve(distinctKeys, options.resolverMode(), options.refreshTerms());
            var outcomesByUri = mode == ResolverMode.OFF
                    ? Map.<String, AnnotationEnrichmentOutcome>of()
                    : enrich(resourcePathUris, options);
            var resourceMode = mode.wire();

            long indexed = 0;
            long skipped = 0;
            long failed = 0;
            for (var resource : resources) {
                var resourceId = resource.getKey();
                var termKey = keysByResource.get(resourceId);

                if (termKey == null) {
                    if (mode == ResolverMode.OFF) {
                        markResource(session, resourceId, "skipped", OFF_MODE_NON_ONTOLOGICAL_REASON, resourceMode, null);
                        skipped++;
                        continue;
                    }
                    var outcome = outcomesByUri.get(resource.getValue());
                    if (outcome == null || outcome.status() == AnnotationEnrichmentOutcome.Status.FAILED) {
                        markResource(session, resourceId, "failed", failureReason(outcome), resourceMode, null);
                        failed++;
                        continue;
                    }
                    if (!outcome.indexed()) {
                        markResource(session, resourceId, "skipped", outcome.failureReason(), resourceMode, null);
                        skipped++;
                        continue;
                    }
                    writeEnrichedText(
                            session,
                            resourceId,
                            outcome.searchableText(),
                            resourceMode,
                            TermDefinition.Source.PAGE,
                            null);
                    indexed++;
                    continue;
                }

                var definition = resolution.definition(termKey);
                if (definition == null || !definition.resolved()) {
                    markResource(session, resourceId, "skipped", unresolvedReason(termKey, definition), resourceMode, termKey.wire());
                    skipped++;
                    continue;
                }
                writeEnrichedText(session, resourceId, definition.keywordText(), resourceMode, definition.source(), termKey.wire());
                indexed++;
            }
            return new AnnotationIndexingResult(resources.size(), indexed, skipped, failed, resolution.summary());
        }
    }

    /** The resource's term key, or {@code null} when its URI is not an ontological term. */
    private TermKey termKeyOf(Map.Entry<String, String> resource) {
        try {
            return catalogue.termKey(resource.getValue()).orElse(null);
        } catch (RuntimeException exception) {
            // A URI the catalogue cannot read is a resource-path URI, which is what it always was.
            return null;
        }
    }

    private static String unresolvedReason(TermKey key, TermDefinition definition) {
        if (definition == null || definition.failureReason() == null) {
            return key.wire() + ": term not resolved by this run";
        }
        return key.wire() + ": " + definition.failureReason();
    }

    private static void dropPreviousEnrichment(Session session) {
        session.executeWriteWithoutResult(tx -> tx.run("""
                MATCH (resource:RESOURCE)
                REMOVE resource.isIndexed, resource.resourceText, resource.indexStatus, resource.indexedAt, resource.indexFailure, resource.resolutionMode, resource.resolvedVia, resource.termKey
                """).consume());
        session.executeWriteWithoutResult(tx -> tx.run("""
                MATCH (model:Model)
                REMOVE model.resourceText
                """).consume());
    }

    private static List<Map.Entry<String, String>> pendingResources(Session session, AnnotationIndexingOptions options) {
        var query = AnnotationPendingResourceQuery.pendingResources(options);
        return session.executeRead(tx -> tx.run(query, Map.of("limit", options.limit()))
                .list(record -> Map.entry(record.get("id").asString(), record.get("uri").asString())));
    }

    private Map<String, AnnotationEnrichmentOutcome> enrich(
            List<String> resourceUris,
            AnnotationIndexingOptions options
    ) {
        if (resourceUris.isEmpty()) {
            return Map.of();
        }
        var request = new AnnotationEnrichmentRequest(
                resourceUris,
                options.maxConcurrency(),
                MAX_CANDIDATES_PER_RESOURCE,
                AnnotationEnrichmentRequest.DEFAULT_MAX_TEXT_LENGTH
        );
        var outcomes = new HashMap<String, AnnotationEnrichmentOutcome>();
        for (var outcome : enrichmentService.enrich(request)) {
            outcomes.put(outcome.resourceUri(), outcome);
        }
        return outcomes;
    }

    private static String failureReason(AnnotationEnrichmentOutcome outcome) {
        if (outcome == null) {
            return "not processed";
        }
        return outcome.failureReason() == null ? "annotation enrichment failed" : outcome.failureReason();
    }

    /**
     * Writes the text and its provenance. {@code resolvedVia} is written whenever text is, and
     * {@code termKey} only for a term-backed resource, so a resource-path write leaves no stale key.
     *
     * <p>An absent key is written as Cypher {@code null}, which removes the property: an empty string
     * would be a present property, so no reader could tell "has a term" from "has no term".
     */
    private static void writeEnrichedText(
            Session session,
            String resourceId,
            String text,
            String resolutionMode,
            TermDefinition.Source source,
            String termKey
    ) {
        var parameters = provenanceParameters(resourceId, resolutionMode, termKey);
        parameters.put("resourceText", text);
        parameters.put("resolvedVia", source == null ? null : source.name().toLowerCase(Locale.ROOT));
        session.executeWriteWithoutResult(tx -> tx.run("""
                MATCH (resource:RESOURCE)
                WHERE elementId(resource) = $resourceId
                SET resource.resourceText = $resourceText,
                    resource.isIndexed = true,
                    resource.indexStatus = 'indexed',
                    resource.indexedAt = datetime(),
                    resource.indexFailure = null,
                    resource.resolutionMode = $resolutionMode,
                    resource.resolvedVia = coalesce($resolvedVia, ''),
                    resource.termKey = $termKey
                WITH resource
                MATCH (resource)-[*1..4]-(model:Model)
                SET model.resourceText = trim(coalesce(model.resourceText + ' ', '') + $resourceText)
                """, parameters).consume());
    }

    /**
     * Marks a resource failed or skipped and removes the provenance of whatever it described before,
     * so a skipped resource cannot keep the previous run's mode, source or term. Only a skipped
     * ontological resource still carries its {@code termKey}: that is the term it was skipped for.
     */
    private static void markResource(
            Session session,
            String resourceId,
            String status,
            String reason,
            String resolutionMode,
            String termKey
    ) {
        try {
            var parameters = provenanceParameters(resourceId, resolutionMode, termKey);
            parameters.put("status", status);
            parameters.put("reason", reason == null ? "" : reason);
            session.executeWriteWithoutResult(tx -> tx.run("""
                    MATCH (resource:RESOURCE)
                    WHERE elementId(resource) = $resourceId
                    SET resource.isIndexed = false,
                        resource.indexStatus = $status,
                        resource.indexFailure = $reason,
                        resource.resolutionMode = $resolutionMode,
                        resource.termKey = $termKey
                    REMOVE resource.resolvedVia
                    """, parameters).consume());
        } catch (RuntimeException exception) {
            // Status bookkeeping must never abort the maintenance run.
        }
    }

    /**
     * The provenance parameters both writers share. A mutable map, because {@code Map.of} rejects the
     * {@code null} that carries "this resource has no term"; the driver turns it into a Cypher
     * {@code null}, and {@code SET n.prop = null} removes the property.
     */
    private static Map<String, Object> provenanceParameters(String resourceId, String resolutionMode, String termKey) {
        var parameters = new HashMap<String, Object>();
        parameters.put("resourceId", resourceId);
        parameters.put("resolutionMode", resolutionMode);
        parameters.put("termKey", termKey);
        return parameters;
    }
}

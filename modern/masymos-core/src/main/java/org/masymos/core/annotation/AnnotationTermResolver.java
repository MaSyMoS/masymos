package org.masymos.core.annotation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.masymos.core.model.AnnotationIndexingResult.TermResolutionSummary;

/**
 * Storage-neutral ontology term stage of one annotation run: one cache read, one provider stage over
 * the misses, one page fallback, one cache write.
 *
 * <p>The mode is a truncation of one chain, not three code paths ({@code providers} — providers, then
 * the page fallback; {@code direct} — the page fallback only; {@code off} — neither), and the
 * resolver never throws: every key it accepted has an entry in {@link Resolution#definitions()}, and a
 * throwing provider is demoted and skipped — its keys stay in the chain — while a throwing fetcher
 * becomes a per-key unresolved definition.
 *
 * <p>Freshness (the design's answers 7 and 9): a cached definition is fresh when it is within the
 * TTL, it is not a {@link TermDefinition.FailureClass#TRANSIENT} entry — which is never fresh, in any
 * mode — the call is not a refresh, and, in {@link ResolverMode#PROVIDERS}, it did not come from the
 * page, so one provider-capable run upgrades page text once. A {@link
 * TermDefinition.FailureClass#DEFINITIVE} answer is fresh until the TTL expires, <em>provided</em> it
 * names the question that produced it (see below).
 *
 * <p>Fingerprints: a cached {@link TermDefinition.FailureClass#DEFINITIVE} negative is only as good as
 * the lookup that asked. Measured 2026-11-13/14, a wrong IRI template turned every SBO and FMA term
 * into a cached 404, and a wrong endpoint did the same for {@code KISAO_0000245}, an object property;
 * each stayed believable for the whole {@link #DEFAULT_TTL}. Every cache write therefore stores the
 * current {@link AnnotationPrefixCatalogue#lookupFingerprint(TermKey)}, and a definitive negative
 * whose fingerprint is absent or different from the current one is a miss: it is refetched and
 * rewritten with the current fingerprint, which is the whole healing mechanism. A {@link
 * TermDefinition.Status#RESOLVED} entry is exempt whatever its fingerprint — a 200 body cannot belong
 * to a different question — so a warm resolved cache is never refetched wholesale.
 *
 * <p>Only ontological keys enter the stage: a key the catalogue does not know as an ontology gets no
 * definition, no provider request and no cache entry.
 *
 * <p>A prefix whose identifiers.org page yields no entity text (see
 * {@link AnnotationPrefixCatalogue#pageTextAvailable(TermKey)}) is never fetched. Which answer it gets
 * depends on whether the run has a source at all: if a configured provider publishes the prefix's
 * definitions and synonyms ({@link AnnotationPrefixCatalogue#definitionSources(TermKey)}), the page's
 * silence is knowledge about the page and is recorded as a {@code DEFINITIVE} {@code "no page text for
 * this prefix"} negative, cached for the TTL. If no configured provider is such a source — any
 * {@code direct} run, and FMA under an OLS4-only {@code providers} run, whose definitions live at
 * BioPortal alone — the reason is {@code "no text source configured for this prefix"}: a fact about the
 * configuration, recorded {@code TRANSIENT} and never cached, because a missing key must not become
 * knowledge about a term. Neither path counts a page request, since no page is read.
 *
 * <p>{@code off} calls neither a provider nor the page fetcher and writes nothing to the cache.
 */
public final class AnnotationTermResolver {

    public static final Duration DEFAULT_TTL = Duration.ofDays(30);

    private static final String NO_PROVIDER_REASON = "no configured provider supports this prefix";
    private static final String NO_PAGE_TEXT_REASON = "no page text for this prefix";
    private static final String NO_TEXT_SOURCE_REASON = "no text source configured for this prefix";
    private static final String NO_CACHE_REASON = "off mode: term not in cache";
    private static final String UNKNOWN = "unknown";

    private final AnnotationPrefixCatalogue catalogue;
    private final List<AnnotationTermProvider> providers;
    private final AnnotationTermCache cache;
    private final Clock clock;
    private final Duration ttl;
    private final AnnotationResourceFetcher pageFetcher;
    private final AnnotationTextExtractor textExtractor;
    private final int maxTextLength;

    public AnnotationTermResolver(
            AnnotationPrefixCatalogue catalogue,
            List<AnnotationTermProvider> providers,
            AnnotationTermCache cache,
            Clock clock,
            Duration ttl,
            AnnotationResourceFetcher pageFetcher,
            AnnotationTextExtractor textExtractor,
            int maxTextLength
    ) {
        this.catalogue = Objects.requireNonNull(catalogue, "catalogue");
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.cache = Objects.requireNonNull(cache, "cache");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = ttl == null || ttl.isNegative() ? DEFAULT_TTL : ttl;
        this.pageFetcher = Objects.requireNonNull(pageFetcher, "pageFetcher");
        this.textExtractor = Objects.requireNonNull(textExtractor, "textExtractor");
        this.maxTextLength = maxTextLength < 1 ? AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH : maxTextLength;
    }

    /**
     * Resolves one run's distinct terms. Never throws.
     *
     * @param keys the run's term keys; duplicates and non-ontological keys are dropped
     * @param mode how far the chain may go; {@code null} means {@link ResolverMode#DIRECT}
     * @param refreshTerms treat every cached entry as stale for this call, and this call only
     */
    public Resolution resolve(Collection<TermKey> keys, ResolverMode mode, boolean refreshTerms) {
        var effectiveMode = mode == null ? ResolverMode.DIRECT : mode;
        var now = clock.instant();
        var counters = new Counters();
        var providersAvailable = new ArrayList<String>();
        for (var provider : providers) {
            providersAvailable.add(sourceName(provider));
        }

        var definitions = new LinkedHashMap<TermKey, TermDefinition>();
        var cacheWrites = new ArrayList<AnnotationTermCache.CachedTerm>();
        var demoted = new LinkedHashSet<String>();

        var ontological = ontologicalKeys(keys);
        counters.termsRequested = ontological.size();

        Map<TermKey, AnnotationTermCache.CachedTerm> cached = Map.of();
        if (!ontological.isEmpty()) {
            try {
                var found = cache.findAll(ontological);
                cached = found == null ? Map.of() : found;
            } catch (RuntimeException exception) {
                // An unreadable cache is a cold cache: every term becomes a miss and is retried.
                // Counted so an unreadable cache is not mistaken for a cold one.
                counters.cacheFailures++;
                cached = Map.of();
            }
        }

        var pending = new ArrayList<TermKey>();
        for (var key : ontological) {
            var entry = cached.get(key);
            if (isFresh(entry, effectiveMode, refreshTerms, now)) {
                definitions.put(key, entry.definition());
                counters.cacheHits++;
            } else {
                counters.cacheMisses++;
                pending.add(key);
            }
        }

        if (!pending.isEmpty()) {
            // One supports() answer per (source, key) for the whole run: asking a peer twice is a
            // request the run does not need, and the answer is a configuration fact, not I/O.
            var supportsMemo = new java.util.HashMap<String, Boolean>();
            switch (effectiveMode) {
                case OFF -> recordUnresolved(definitions, pending, NO_CACHE_REASON);
                case DIRECT -> pageFallback(pending, definitions, cacheWrites, now, counters, false);
                case PROVIDERS -> providerChain(
                        pending, definitions, cacheWrites, now, counters, demoted, supportsMemo);
            }
        }

        if (effectiveMode != ResolverMode.OFF && !cacheWrites.isEmpty()) {
            try {
                cache.saveAll(cacheWrites);
            } catch (RuntimeException exception) {
                // A failed cache write costs the cache, never the run.
                // Counted: a silently unwritable cache otherwise looks like a cold one.
                counters.cacheFailures++;
            }
        }

        for (var definition : definitions.values()) {
            if (!definition.resolved()) {
                counters.unresolvedTerms++;
            }
        }

        var summary = new TermResolutionSummary(
                effectiveMode,
                List.copyOf(providersAvailable),
                counters.termsRequested,
                counters.providerRequests,
                counters.cacheHits,
                counters.cacheMisses,
                counters.unresolvedTerms,
                List.copyOf(demoted),
                counters.pageRequests,
                counters.cacheFailures);
        return new Resolution(definitions, summary);
    }

    private void providerChain(
            List<TermKey> pending,
            Map<TermKey, TermDefinition> definitions,
            List<AnnotationTermCache.CachedTerm> cacheWrites,
            Instant now,
            Counters counters,
            Set<String> demoted,
            Map<String, Boolean> supportsMemo
    ) {
        var remaining = new LinkedHashSet<>(pending);
        for (var provider : providers) {
            if (remaining.isEmpty()) {
                return;
            }
            var name = sourceName(provider);
            if (demoted.contains(name)) {
                continue;
            }
            var supported = new ArrayList<TermKey>();
            for (var key : remaining) {
                var memoKey = name + "#" + key.wire();
                var known = supportsMemo.get(memoKey);
                if (known == null) {
                    try {
                        known = provider.supports(key);
                    } catch (RuntimeException exception) {
                        // A peer that cannot answer the question is asked about the key anyway: silence
                        // is not a refusal, and dropping the key would lose the term.
                        known = true;
                    }
                    supportsMemo.put(memoKey, known);
                }
                if (known) {
                    supported.add(key);
                }
            }
            if (supported.isEmpty()) {
                continue;
            }

            Map<TermKey, TermDefinition> answered;
            counters.providerRequests++;
            try {
                answered = provider.resolveAll(List.copyOf(supported));
            } catch (RuntimeException exception) {
                // Transport-level failure: demote, write nothing, and leave the keys in
                // `remaining` so the next provider and then the page fallback still answer them.
                demoted.add(name);
                continue;
            }

            if (answered == null) {
                demoted.add(name);
                continue;
            }

            // A key this batch does not answer stays in `remaining` for the next provider. That is
            // not a demotion: the provider answered the batch, it just had nothing for this key.
            for (var key : supported) {
                var definition = answered.get(key);
                if (definition == null) {
                    continue;
                }
                definitions.put(key, definition);
                remaining.remove(key);
                // Both classes are stored for diagnosis; only a DEFINITIVE one is ever fresh, and
                // only while it names the lookup that produced it.
                cacheWrites.add(write(definition, now));
            }
        }

        var rest = new ArrayList<>(remaining);
        if (!rest.isEmpty()) {
            pageFallback(rest, definitions, cacheWrites, now, counters, true);
        }
    }

    private void pageFallback(
            Collection<TermKey> keys,
            Map<TermKey, TermDefinition> definitions,
            List<AnnotationTermCache.CachedTerm> cacheWrites,
            Instant now,
            Counters counters,
            boolean providersInPlay
    ) {
        var unserved = 0;
        for (var key : keys) {
            String url = null;
            try {
                if (!pageTextAvailable(key)) {
                    // Measured 2026-11-16: the GO, FMA, SBO and KiSAO pages yield no entity text (403,
                    // SPA shell, resource deprecation notice). The request could only fail, so it is not
                    // issued at all and it is not counted as a page request because no page was read.
                    unserved++;
                    TermDefinition definition;
                    if (hasConfiguredTextSource(key, providersInPlay)) {
                        // A source was configured and did not answer: the page's silence is knowledge
                        // about the page, so it is definitive and cached for the TTL.
                        definition = TermDefinition.unresolved(key, TermDefinition.FailureClass.DEFINITIVE,
                                NO_PAGE_TEXT_REASON);
                        cacheWrites.add(write(definition, now));
                    } else {
                        // No configured provider publishes this prefix's definitions and synonyms (FMA
                        // under OLS4-only, and every direct run). That is a fact about the configuration,
                        // not about the term, so it is transient and not written: adding the missing key
                        // must heal the next run, not wait for the TTL.
                        definition = transientUnresolved(key, NO_TEXT_SOURCE_REASON);
                    }
                    definitions.put(key, definition);
                    continue;
                }
                url = catalogue.canonicalPageUrl(key).orElse(null);
            } catch (RuntimeException exception) {
                url = null;
            }
            if (url == null || url.isBlank()) {
                // No configured provider serves this prefix: definitive, so cached for the TTL.
                unserved++;
                var definition = TermDefinition.unresolved(key, TermDefinition.FailureClass.DEFINITIVE,
                        NO_PROVIDER_REASON);
                definitions.put(key, definition);
                cacheWrites.add(write(definition, now));
                continue;
            }
            definitions.put(key, fetchPage(key, url, cacheWrites, now));
        }
        counters.pageRequests += keys.size() - unserved;
    }

    /**
     * Whether a page fetch for this key can learn anything: the catalogue's per-prefix answer, and
     * {@code true} when the catalogue does not know the key, because the caller then falls through to
     * the {@link #NO_PROVIDER_REASON} path as before. Never throws.
     */
    private boolean pageTextAvailable(TermKey key) {
        try {
            return catalogue.pageTextAvailable(key).orElse(true);
        } catch (RuntimeException exception) {
            return true;
        }
    }

    /**
     * Whether a provider configured for this run publishes the prefix's definitions and synonyms.
     *
     * <p>{@code false} in {@code direct} mode: no provider is called there, so for a page that yields
     * no text there is no source at all. In {@code providers} mode it is true when any configured
     * provider's {@link AnnotationTermProvider#source() source} is among the catalogue's
     * {@link AnnotationPrefixCatalogue#definitionSources(TermKey) declared sources} for the prefix —
     * FMA's set is BioPortal alone, so an OLS4-only run has none. An unknown prefix, or a catalogue
     * that cannot answer, keeps the older page answer rather than inventing a configuration fact.
     */
    private boolean hasConfiguredTextSource(TermKey key, boolean providersInPlay) {
        if (!providersInPlay) {
            return false;
        }
        Set<TermDefinition.Source> sources;
        try {
            sources = catalogue.definitionSources(key);
        } catch (RuntimeException exception) {
            return true;
        }
        if (sources.isEmpty()) {
            return true;
        }
        for (var provider : providers) {
            try {
                if (sources.contains(provider.source())) {
                    return true;
                }
            } catch (RuntimeException exception) {
                // A provider that cannot name its source cannot be the configured source.
            }
        }
        return false;
    }

    private TermDefinition fetchPage(
            TermKey key,
            String url,
            List<AnnotationTermCache.CachedTerm> cacheWrites,
            Instant now
    ) {
        AnnotationFetchResult fetched;
        try {
            fetched = pageFetcher.fetch(url);
        } catch (RuntimeException exception) {
            return transientUnresolved(key, "page fetch failed: " + reason(exception, "fetcher"));
        }
        if (fetched == null) {
            return transientUnresolved(key, "page fetch failed: no result for " + url);
        }
        if (!fetched.succeeded()) {
            var detail = fetched.failureReason() == null ? "http status " + fetched.statusCode() : fetched.failureReason();
            // Answer from 7: a failed fetch is transient — retry next run, never cache as knowledge.
            return transientUnresolved(key, "page fetch failed: " + detail);
        }

        String text;
        try {
            text = textExtractor.extract(fetched.contentType(), fetched.body());
        } catch (RuntimeException exception) {
            return transientUnresolved(key, "page extraction failed: " + reason(exception, "extractor"));
        }
        text = text == null ? "" : text.trim();
        if (text.isBlank()) {
            return transientUnresolved(key, "page fetch yielded no extractable text: " + url);
        }
        if (text.length() > maxTextLength) {
            text = text.substring(0, maxTextLength);
        }
        var definition = TermDefinition.resolved(key, text, TermDefinition.Source.PAGE);
        cacheWrites.add(write(definition, now));
        return definition;
    }

    /**
     * The cache entry for a freshly learned answer, stamped with the lookup question that produced it.
     *
     * <p>The fingerprint is what makes a negative answer checkable later: a {@code DEFINITIVE} "no such
     * class" recorded under a wrong IRI template or a wrong endpoint was measured (2026-11-13/14, every
     * SBO and FMA term, and {@code KISAO_0000245}) to poison the cache for the whole TTL. Storing it
     * lets {@link #isFresh} reject that entry once the question changes.
     */
    private AnnotationTermCache.CachedTerm write(TermDefinition definition, Instant now) {
        return AnnotationTermCache.CachedTerm.of(definition, now, fingerprint(definition.key()));
    }

    /** The current lookup fingerprint of a key; {@code null} when the catalogue does not know it. */
    private String fingerprint(TermKey key) {
        try {
            return catalogue.lookupFingerprint(key).orElse(null);
        } catch (RuntimeException exception) {
            // A catalogue that cannot name the question yields an unknown fingerprint. That only
            // costs a definitive negative its freshness, never the run.
            return null;
        }
    }

    private static TermDefinition transientUnresolved(TermKey key, String reason) {
        return TermDefinition.unresolved(key, TermDefinition.FailureClass.TRANSIENT, reason);
    }

    /**
     * An {@code off} miss: recorded transient — no fetch happened, so nothing was learned — and
     * deliberately not written to the cache, because {@code off} writes nothing.
     */
    private static void recordUnresolved(
            Map<TermKey, TermDefinition> definitions,
            Collection<TermKey> keys,
            String reason
    ) {
        for (var key : keys) {
            definitions.put(key, transientUnresolved(key, reason));
        }
    }

    /** Every ontological key of the input, de-duplicated, in input order. Never throws. */
    private List<TermKey> ontologicalKeys(Collection<TermKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        var distinct = new LinkedHashSet<TermKey>();
        for (var key : keys) {
            if (key == null) {
                continue;
            }
            try {
                if (catalogue.isOntological(key.prefix())) {
                    distinct.add(key);
                }
            } catch (RuntimeException exception) {
                // A key the catalogue cannot even look at is not ontological.
            }
        }
        return List.copyOf(distinct);
    }

    private boolean isFresh(
            AnnotationTermCache.CachedTerm cached,
            ResolverMode mode,
            boolean refreshTerms,
            Instant now
    ) {
        if (cached == null || cached.definition() == null) {
            return false;
        }
        if (refreshTerms) {
            return false;
        }
        var definition = cached.definition();
        if (!definition.resolved()) {
            // Answer 7: a transient entry is a miss on every read; a definitive one lives for the TTL,
            // but only while it names the question that produced it.
            if (definition.failureClass() != TermDefinition.FailureClass.DEFINITIVE) {
                return false;
            }
            return withinTtl(cached.fetchedAt(), now) && sameQuestionAsNow(cached);
        }
        if (!withinTtl(cached.fetchedAt(), now)) {
            return false;
        }
        // Answer 9: page text is not provider text; one provider-capable run may upgrade it.
        // A resolved entry is exempt from the fingerprint rule: a 200 body cannot belong to a
        // different question, so a warm resolved cache is never refetched wholesale.
        return mode != ResolverMode.PROVIDERS || definition.source() != TermDefinition.Source.PAGE;
    }

    /**
     * Whether a definitive negative was produced by the lookup that would be asked now.
     *
     * <p>A {@code null} fingerprint means the entry was written before fingerprints existed, so its
     * question is unknown; an entry with a different fingerprint was produced by a different question
     * — a changed IRI template, ontology id or resolution strategy. Either way the answer is not fresh,
     * it is refetched, and it is rewritten with the current fingerprint.
     */
    private boolean sameQuestionAsNow(AnnotationTermCache.CachedTerm cached) {
        var recorded = cached.catalogueFingerprint();
        if (recorded == null) {
            return false;
        }
        return recorded.equals(fingerprint(cached.key()));
    }

    private boolean withinTtl(Instant fetchedAt, Instant now) {
        if (fetchedAt == null) {
            return false;
        }
        return fetchedAt.plus(ttl).isAfter(now);
    }

    private static String sourceName(AnnotationTermProvider provider) {
        try {
            var source = provider.source();
            return source == null ? UNKNOWN : source.name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return UNKNOWN;
        }
    }

    private static String reason(RuntimeException exception, String fallback) {
        var message = exception.getMessage();
        var type = exception.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    /** One run's outcome: one definition per accepted key, plus the run summary. */
    public record Resolution(Map<TermKey, TermDefinition> definitions, TermResolutionSummary summary) {

        public Resolution {
            Objects.requireNonNull(summary, "summary");
            var copy = new LinkedHashMap<TermKey, TermDefinition>();
            if (definitions != null) {
                definitions.forEach((key, definition) -> {
                    if (key != null && definition != null) {
                        copy.put(key, definition);
                    }
                });
            }
            definitions = Map.copyOf(copy);
        }

        /** The definition for a key, or {@code null} when the key was not part of this run. */
        public TermDefinition definition(TermKey key) {
            return definitions.get(key);
        }

        public boolean resolved(TermKey key) {
            var definition = definitions.get(key);
            return definition != null && definition.resolved();
        }
    }

    private static final class Counters {
        private long termsRequested;
        private long providerRequests;
        private long pageRequests;
        private long cacheHits;
        private long cacheMisses;
        private long cacheFailures;
        private long unresolvedTerms;
    }
}

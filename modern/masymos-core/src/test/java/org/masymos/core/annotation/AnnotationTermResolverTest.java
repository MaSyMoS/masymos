package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.masymos.core.annotation.AnnotationTermCache.CachedTerm;
import org.masymos.core.annotation.TermDefinition.FailureClass;
import org.masymos.core.annotation.TermDefinition.Source;
import org.masymos.core.annotation.TermDefinition.Status;

class AnnotationTermResolverTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Duration TTL = Duration.ofDays(30);
    private static final TermKey GO = new TermKey("go", "0008150");
    /** The only prefix whose identifiers.org page yields entity text (measured 2026-11-16). */
    private static final TermKey CHEBI = new TermKey("chebi", "15377");
    private static final TermKey CHEBI_2 = new TermKey("chebi", "16526");
    private static final TermKey KISAO = new TermKey("kisao", "0000019");

    private final AnnotationPrefixCatalogue catalogue = AnnotationPrefixCatalogue.standard();
    private final AnnotationTextExtractor extractor = new AnnotationTextExtractor();
    private final RecordingCache cache = new RecordingCache();

    // ---- 1. fresh cache hit -------------------------------------------------------------------

    @Test
    void freshCacheHitIsReturnedWithoutAskingAProvider() {
        cache.put(Source.BIOPORTAL, GO, "cached GO text", NOW.minus(Duration.ofDays(1)));
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var resolver = resolver(List.of(provider), url -> {
            throw new AssertionError("page fetcher must not be called on a fresh hit: " + url);
        });

        var result = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);

        assertEquals("cached GO text", result.definition(GO).keywordText());
        assertEquals(Source.BIOPORTAL, result.definition(GO).source());
        assertEquals(0, provider.calls.get());
        assertTrue(cache.saves.isEmpty());
        assertEquals(1, result.summary().cacheHits());
        assertEquals(0, result.summary().cacheMisses());
    }

    // ---- 2. transient is never fresh ----------------------------------------------------------

    @Test
    void transientFailureIsNeverFreshInAnyMode() {
        for (var mode : List.of(ResolverMode.PROVIDERS, ResolverMode.DIRECT)) {
            cache.clear();
            cache.saveAll(List.of(new CachedTerm(
                    TermDefinition.unresolved(CHEBI, FailureClass.TRANSIENT, "timeout"), NOW.minusSeconds(1))));
            var provider = FakeProvider.answering(Source.BIOPORTAL, CHEBI);
            var resolver = resolver(List.of(provider), url -> new AnnotationFetchResult(url, 200, "text/plain", "page CHEBI", null));

            var result = resolver.resolve(List.of(CHEBI), mode, false);

            assertEquals(1, result.summary().cacheMisses(), "transient must be a miss in " + mode);
            assertEquals(0, result.summary().cacheHits());
            if (mode == ResolverMode.PROVIDERS) {
                assertEquals(1, provider.calls.get());
            }
            assertTrue(result.resolved(CHEBI), "the retry must have learned the term in " + mode);
        }
    }

    // ---- 3. definitive is fresh until the TTL expires -----------------------------------------

    @Test
    void definitiveFailureIsFreshUntilTtlThenRetried() {
        var definitive = TermDefinition.unresolved(GO, FailureClass.DEFINITIVE, "no such class");
        var fingerprint = catalogue.lookupFingerprint(GO).orElseThrow();
        cache.saveAll(List.of(CachedTerm.of(definitive, NOW.minus(TTL).plusSeconds(1), fingerprint)));
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "http status 404"));

        var withinTtl = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);
        assertEquals(1, withinTtl.summary().cacheHits());
        assertEquals(0, provider.calls.get());
        assertFalse(withinTtl.resolved(GO));

        cache.clear();
        cache.saveAll(List.of(CachedTerm.of(definitive, NOW.minus(TTL).minusSeconds(1), fingerprint)));
        var pastTtl = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);
        assertEquals(0, pastTtl.summary().cacheHits());
        assertEquals(1, pastTtl.summary().cacheMisses());
        assertEquals(1, provider.calls.get());
        assertTrue(pastTtl.resolved(GO));
    }

    // ---- 3b. a definitive negative names its lookup question --------------------------------

    @Test
    void definitiveFailureWithTheCurrentFingerprintIsFresh() {
        var fingerprint = catalogue.lookupFingerprint(GO).orElseThrow();
        var definitive = TermDefinition.unresolved(GO, FailureClass.DEFINITIVE, "no such class");
        cache.saveAll(List.of(CachedTerm.of(definitive, NOW.minus(Duration.ofDays(1)), fingerprint)));
        cache.saves.clear();
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "http status 404"));

        var result = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);

        assertEquals(1, result.summary().cacheHits(), "the same question answered: still fresh");
        assertEquals(0, provider.calls.get());
        assertFalse(result.resolved(GO));
        assertTrue(cache.saves.isEmpty(), "a fresh hit writes nothing");
    }

    @Test
    void definitiveFailureWithoutAFingerprintIsAMissAndIsRewritten() {
        var definitive = TermDefinition.unresolved(GO, FailureClass.DEFINITIVE, "no such class");
        // Written before fingerprints existed: its question is unknown, so it must not be trusted.
        cache.saveAll(List.of(CachedTerm.of(definitive, NOW.minus(Duration.ofDays(1)))));
        var provider = FakeProvider.answering(Source.BIOPORTAL);
        provider.answerUnresolved(GO, FailureClass.DEFINITIVE, "no such class");
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "http status 404"));

        var result = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);

        assertEquals(1, result.summary().cacheMisses(), "an unknown question is a miss");
        assertEquals(0, result.summary().cacheHits());
        assertEquals(1, provider.calls.get(), "it is refetched");
        assertEquals(catalogue.lookupFingerprint(GO).orElseThrow(),
                cache.savedByKey().get(GO).catalogueFingerprint(),
                "the rewritten entry carries the current fingerprint");
    }

    /**
     * Before ANNO-1f every page-less prefix was cached as a {@code DEFINITIVE} {@code "no page text
     * for this prefix"} in every mode. Those entries answered a different question — whether the page
     * has text — so decision 30's rule applies: the resolution strategy is part of the fingerprint,
     * the old answers retire, and a warm store does not keep serving the claim the new reason
     * replaces.
     */
    @Test
    void aNoPageTextNegativeFromTheOldQuestionIsAMissAndIsNotRewritten() {
        var current = catalogue.lookupFingerprint(GO).orElseThrow();
        var oldQuestion = "v4-merged-ids" + current.substring(current.indexOf('|'));
        cache.saveAll(List.of(CachedTerm.of(
                TermDefinition.unresolved(GO, FailureClass.DEFINITIVE, "no page text for this prefix"),
                NOW.minus(Duration.ofDays(1)), oldQuestion)));
        cache.saves.clear();

        var result = resolver(List.of(), url -> {
            throw new AssertionError("a page that yields no text is never fetched: " + url);
        }).resolve(List.of(GO), ResolverMode.DIRECT, false);

        assertEquals(0, result.summary().cacheHits(), "the old question's answer is not this question's");
        assertEquals(1, result.summary().cacheMisses());
        assertTrue(result.definition(GO).failureReason().contains("no text source configured for this prefix"),
                result.definition(GO).failureReason());
        assertTrue(cache.saves.isEmpty(), "a configuration fact is not cached");
    }

    @Test
    void definitiveFailureFromADifferentQuestionIsAMissAndIsRewritten() {
        var definitive = TermDefinition.unresolved(GO, FailureClass.DEFINITIVE, "no such class");
        cache.saveAll(List.of(CachedTerm.of(definitive, NOW.minus(Duration.ofDays(1)), "v1-wrong|template|GO|go")));
        var ols4 = new FakeProvider(Source.OLS4, key -> true);
        var resolver = resolver(List.of(ols4), url -> AnnotationFetchResult.failure(url, "http status 404"));

        var result = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);

        assertEquals(1, result.summary().cacheMisses(), "a foreign fingerprint is a miss");
        assertEquals(1, ols4.calls.get());
        assertTrue(result.resolved(GO), "the retry learns the term, so the negative heals");
        var rewritten = cache.savedByKey().get(GO);
        assertEquals(catalogue.lookupFingerprint(GO).orElseThrow(), rewritten.catalogueFingerprint());
        assertEquals(Status.RESOLVED, rewritten.definition().status());
    }

    @Test
    void resolvedEntriesAreExemptFromTheFingerprintRule() {
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"));

        cache.saveAll(List.of(CachedTerm.of(
                TermDefinition.resolved(GO, "cached GO text", Source.BIOPORTAL), NOW.minusSeconds(60))));
        cache.saves.clear();
        var withoutFingerprint = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);
        assertEquals(1, withoutFingerprint.summary().cacheHits(), "a resolved entry with no fingerprint is fresh");
        assertEquals("cached GO text", withoutFingerprint.definition(GO).keywordText());

        cache.clear();
        cache.saveAll(List.of(CachedTerm.of(
                TermDefinition.resolved(GO, "cached GO text", Source.BIOPORTAL), NOW.minusSeconds(60),
                "v1-wrong|template|GO|go")));
        cache.saves.clear();
        var foreignFingerprint = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);
        assertEquals(1, foreignFingerprint.summary().cacheHits(), "a 200 body cannot belong to a wrong question");
        assertEquals(0, provider.calls.get(), "a warm resolved cache is never refetched wholesale");
        assertTrue(cache.saves.isEmpty());
    }

    @Test
    void transientFailureIsAMissWhateverItsFingerprint() {
        var fingerprint = catalogue.lookupFingerprint(GO).orElseThrow();
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"));

        for (var recorded : java.util.Arrays.asList(fingerprint, null, "v1-wrong|template|GO|go")) {
            cache.clear();
            cache.saveAll(List.of(CachedTerm.of(
                    TermDefinition.unresolved(GO, FailureClass.TRANSIENT, "timeout"), NOW.minusSeconds(1), recorded)));

            var result = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);

            assertEquals(1, result.summary().cacheMisses(),
                    "a transient entry is never fresh, fingerprint=" + recorded);
            assertTrue(result.resolved(GO));
        }
    }

    // ---- 4. page source is stale in providers, fresh in direct --------------------------------

    @Test
    void pageSourceIsStaleInProvidersAndFreshInDirect() {
        // CHEBI is the one prefix whose page still yields text, so it is the one that can prove
        // "page now, provider later"; GO is deliberately not fetched at all any more.
        cache.put(Source.PAGE, CHEBI, "page CHEBI text", NOW.minusSeconds(60));
        var provider = FakeProvider.answering(Source.BIOPORTAL, CHEBI);
        var fetcher = new AtomicInteger();
        var resolver = resolver(List.of(provider), url -> {
            fetcher.incrementAndGet();
            return new AnnotationFetchResult(url, 200, "text/plain", "page CHEBI text", null);
        });

        var direct = resolver.resolve(List.of(CHEBI), ResolverMode.DIRECT, false);
        assertEquals(1, direct.summary().cacheHits());
        assertEquals(Source.PAGE, direct.definition(CHEBI).source());
        assertEquals(0, provider.calls.get());

        var providers = resolver.resolve(List.of(CHEBI), ResolverMode.PROVIDERS, false);
        assertEquals(1, providers.summary().cacheMisses());
        assertEquals(1, provider.calls.get(), "one provider-capable run must upgrade page text once");
        assertEquals(Source.BIOPORTAL, providers.definition(CHEBI).source());
        assertEquals(0, fetcher.get(), "the provider answered, so the page is not needed");
    }

    // ---- 5. refreshTerms is scoped to this call -----------------------------------------------

    @Test
    void refreshTermsMakesEveryEntryStaleForThisCallOnly() {
        cache.put(Source.BIOPORTAL, GO, "old", NOW.minusSeconds(1));
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"));

        var refreshed = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, true);
        assertEquals(1, refreshed.summary().cacheMisses());
        assertEquals(1, provider.calls.get());
        assertEquals("provider GO text", refreshed.definition(GO).keywordText());

        provider.answered.clear();
        var afterwards = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);
        assertEquals(1, afterwards.summary().cacheHits());
        assertEquals(1, provider.calls.get(), "the refresh must not persist as a stale cache state");
    }

    // ---- 6. the mode chain ---------------------------------------------------------------------

    @Test
    void providersAsksProvidersInOrderThenThePageFallback() {
        var first = new FakeProvider(Source.BIOPORTAL, key -> true);
        var second = new FakeProvider(Source.OLS4, key -> key.equals(KISAO));
        var fetcher = new AtomicInteger();
        var resolver = resolver(List.of(first, second), url -> {
            fetcher.incrementAndGet();
            return AnnotationFetchResult.failure(url, "http status 503");
        });

        var result = resolver.resolve(List.of(GO, CHEBI, KISAO), ResolverMode.PROVIDERS, false);

        assertTrue(result.resolved(GO));
        assertTrue(result.resolved(CHEBI));
        assertEquals(List.of(GO, CHEBI, KISAO), first.requested.getFirst(), "one batch, in input order");
        assertEquals(0, second.calls.get(), "nothing was left once the first provider answered");
        assertEquals(0, fetcher.get());
    }

    @Test
    void providersOnlyAskProvidersThatSupportTheKeyThenFallBackToThePage() {
        // Support is decided per key, not per prefix: a provider that supports "chebi" would answer
        // both CHEBI keys, and nothing would reach the page fallback this test is about.
        var bioportal = new FakeProvider(Source.BIOPORTAL, key -> key.equals(CHEBI));
        var ols4 = new FakeProvider(Source.OLS4, key -> false);
        var urls = new ArrayList<String>();
        var resolver = resolver(List.of(bioportal, ols4), url -> {
            urls.add(url);
            return new AnnotationFetchResult(url, 200, "text/plain", "page " + url, null);
        });

        var result = resolver.resolve(List.of(CHEBI, CHEBI_2), ResolverMode.PROVIDERS, false);

        assertEquals(1, bioportal.calls.get());
        assertEquals(0, ols4.calls.get(), "OLS4 does not support either key here");
        assertEquals(List.of("https://identifiers.org/CHEBI:16526"), urls, "only the unserved key");
        assertEquals(Source.BIOPORTAL, result.definition(CHEBI).source());
        assertEquals(Source.PAGE, result.definition(CHEBI_2).source());
    }

    @Test
    void directAsksNoProviderAndOffCallsNothingAtAll() {
        var provider = new ThrowingProvider(Source.BIOPORTAL);
        AnnotationResourceFetcher fetcher = url -> {
            throw new AssertionError("page fetcher must not be called: " + url);
        };
        var directFetcher = new AtomicInteger();
        var directResolver = resolver(List.of(provider), url -> {
            directFetcher.incrementAndGet();
            return new AnnotationFetchResult(url, 200, "text/plain", "page text", null);
        });
        var direct = directResolver.resolve(List.of(CHEBI), ResolverMode.DIRECT, false);
        assertEquals(0, provider.calls.get());
        assertEquals(Source.PAGE, direct.definition(CHEBI).source());

        cache.clear();
        cache.saves.clear();
        var offResolver = resolver(List.of(provider), fetcher);
        var off = offResolver.resolve(List.of(CHEBI), ResolverMode.OFF, false);
        assertEquals(0, provider.calls.get());
        assertEquals(Status.UNRESOLVED, off.definition(CHEBI).status());
        assertTrue(cache.saves.isEmpty(), "off writes nothing to the cache");
        assertEquals(0, off.summary().cacheHits());
        assertFalse(off.resolved(CHEBI));
    }

    @Test
    void offStillAnswersFromAWarmCache() {
        cache.put(Source.OLS4, GO, "cached GO text", NOW.minusSeconds(10));
        var off = resolver(List.of(), url -> {
            throw new AssertionError("off must not fetch");
        }).resolve(List.of(GO), ResolverMode.OFF, false);

        assertTrue(off.resolved(GO));
        assertEquals(1, off.summary().cacheHits());
        assertTrue(cache.saves.isEmpty());
    }

    // ---- 7. demotion ---------------------------------------------------------------------------

    @Test
    void throwingProviderIsDemotedOnceAndTheNextProviderAnswersItsKeys() {
        var failing = new TermKey("go", "0000001");
        var other = new TermKey("chebi", "15377");
        var throwing = new FakeProvider(Source.BIOPORTAL, key -> key.prefix().equals("go"));
        throwing.failWith = new IllegalStateException("connection reset");
        var ols4 = new FakeProvider(Source.OLS4, key -> true);
        var resolver = resolver(List.of(throwing, ols4), url -> AnnotationFetchResult.failure(url, "no page"));

        var result = resolver.resolve(List.of(failing, other), ResolverMode.PROVIDERS, false);

        assertEquals(1, throwing.calls.get(), "a demoted provider is asked at most once in a run");
        assertTrue(result.resolved(failing),
                "a demoted provider must not swallow its keys: the next provider answers them");
        assertEquals(Source.OLS4, result.definition(failing).source());
        assertEquals(Source.OLS4, result.definition(other).source(), "the next provider answers what was left");
        assertEquals(List.of("bioportal"), result.summary().providersDemoted(), "named once each");
        assertEquals(2, result.summary().providerRequests(), "one failed call plus one OLS4 call");
        assertEquals(0, result.summary().pageRequests(), "OLS4 answered everything, so the page is not needed");
        assertTrue(result.definition(failing).resolved(), "no unresolved definition was written for it");
    }

    @Test
    void everyProviderThrowingLeavesTheKeysToThePageFallback() {
        var first = new FakeProvider(Source.BIOPORTAL, key -> true);
        first.failWith = new IllegalStateException("connection reset");
        var second = new FakeProvider(Source.OLS4, key -> true);
        second.failWith = new NullPointerException(); // also covers "returned null" in spirit: nothing written
        var urls = new ArrayList<String>();
        var resolver = resolver(List.of(first, second), url -> {
            urls.add(url);
            return new AnnotationFetchResult(url, 200, "text/plain", "page " + url, null);
        });

        var result = resolver.resolve(List.of(CHEBI, CHEBI_2), ResolverMode.PROVIDERS, false);

        assertEquals(List.of("bioportal", "ols4"), result.summary().providersDemoted());
        assertEquals(2, result.summary().providerRequests());
        assertEquals(List.of("https://identifiers.org/CHEBI:15377", "https://identifiers.org/CHEBI:16526"), urls,
                "every key of the demoted providers reaches the page fallback");
        assertTrue(result.resolved(CHEBI));
        assertTrue(result.resolved(CHEBI_2));
        assertEquals(Source.PAGE, result.definition(CHEBI).source());
        assertEquals(Source.PAGE, result.definition(CHEBI_2).source());
        assertEquals(2, result.summary().pageRequests());
        assertEquals(0, result.summary().unresolvedTerms());
    }

    @Test
    void aProviderThatDropsOneKeyIsNotDemotedAndTheNextProviderAnswersIt() {
        var incomplete = new FakeProvider(Source.BIOPORTAL, key -> true);
        incomplete.omit.add(CHEBI);
        var ols4 = new FakeProvider(Source.OLS4, key -> true);
        var resolver = resolver(List.of(incomplete, ols4), url -> AnnotationFetchResult.failure(url, "no page"));

        var result = resolver.resolve(List.of(GO, CHEBI), ResolverMode.PROVIDERS, false);

        assertEquals(Source.BIOPORTAL, result.definition(GO).source(), "the answered key keeps its answer");
        assertEquals(Source.OLS4, result.definition(CHEBI).source(), "the dropped key moves to the next provider");
        assertTrue(result.summary().providersDemoted().isEmpty(), "a dropped key is not a demotion");
        assertEquals(List.of(CHEBI), ols4.requested.getFirst(), "only what was left is asked again");
        assertEquals(2, result.summary().providerRequests());
        assertEquals(0, result.summary().pageRequests());
    }

    @Test
    void aDemotedProviderIsNotCalledAgainForALaterChunk() {
        // A demoted provider can only be re-asked if the chain is entered twice per run; it is not,
        // so the observable guarantee is that a later chunk never reaches it and its count stays 1.
        var first = new FakeProvider(Source.BIOPORTAL, key -> true);
        first.failWith = new IllegalStateException("connection reset");
        var ols4 = new FakeProvider(Source.OLS4, key -> true);
        var resolver = resolver(List.of(first, ols4), url -> AnnotationFetchResult.failure(url, "no page"));

        var result = resolver.resolve(List.of(GO, CHEBI, new TermKey("go", "0000002")),
                ResolverMode.PROVIDERS, false);

        assertEquals(1, first.calls.get(), "the demoted provider is called exactly once");
        assertEquals(1, ols4.calls.get(), "OLS4 gets every key the demotion left behind");
        assertEquals(3, ols4.requested.getFirst().size());
        assertEquals(List.of("bioportal"), result.summary().providersDemoted());
        assertTrue(result.resolved(GO));
        assertTrue(result.resolved(CHEBI));
    }

    @Test
    void pageRequestsCountsTheKeysThatReachedThePageFallback() {
        // No provider answers, so all three keys reach the page stage — which is the stage under test.
        var bioportal = new FakeProvider(Source.BIOPORTAL, key -> false);
        var urls = new ArrayList<String>();
        var resolver = resolver(List.of(bioportal), url -> {
            urls.add(url);
            return AnnotationFetchResult.failure(url, "http status 503");
        });

        var partiallyFetched = resolver.resolve(List.of(CHEBI, CHEBI_2, KISAO), ResolverMode.PROVIDERS, false);

        assertEquals(2, urls.size(), "CHEBI and CHEBI_2 reached the page, KISAO never does");
        assertEquals(List.of("https://identifiers.org/CHEBI:15377", "https://identifiers.org/CHEBI:16526"), urls);
        assertEquals(2, partiallyFetched.summary().pageRequests(), "a non-page prefix is not a page request");
        assertEquals(FailureClass.TRANSIENT, partiallyFetched.definition(CHEBI).failureClass(),
                "only what the page also failed on is transient");
        assertEquals(FailureClass.DEFINITIVE, partiallyFetched.definition(KISAO).failureClass(),
                "KISAO's page yields no text, so it is never fetched");
        assertTrue(partiallyFetched.definition(KISAO).failureReason().contains("no page text for this prefix"));

        cache.clear();
        var answered = resolver(List.of(FakeProvider.answering(Source.BIOPORTAL, CHEBI, CHEBI_2, KISAO)),
                url -> {
                    throw new AssertionError("a provider answered everything: " + url);
                }).resolve(List.of(CHEBI, CHEBI_2, KISAO), ResolverMode.PROVIDERS, false);

        assertEquals(0, answered.summary().pageRequests(), "0 when a provider answered everything");
    }

    // ---- 7b. cache failures are counted, never fatal -------------------------------------------

    @Test
    void aThrowingCacheReadIsCountedAndTheRunStillResolvesFromAProvider() {
        cache.failFindAll = true;
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"));

        var result = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);

        assertEquals(1, result.summary().cacheFailures(), "an unreadable cache is not a cold one");
        assertEquals(Source.BIOPORTAL, result.definition(GO).source(), "the provider stage still answers");
        assertTrue(result.resolved(GO));
        assertEquals(1, provider.calls.get());
        assertEquals(0, result.summary().cacheHits());
    }

    @Test
    void aThrowingCacheWriteIsCountedAndTheDefinitionsAreStillReturned() {
        cache.failSaveAll = true;
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"));

        var result = resolver.resolve(List.of(GO), ResolverMode.PROVIDERS, false);

        assertEquals(1, result.summary().cacheFailures(), "one failed write");
        assertTrue(result.resolved(GO), "a failed cache write costs the cache, never the run");
        assertEquals(Source.BIOPORTAL, result.definition(GO).source());
        assertEquals(1, result.summary().cacheMisses(), "the read succeeded: the cache was cold, not broken");
    }

    // ---- 8. provider requests are per call, not per term --------------------------------------

    @Test
    void oneProviderRequestPerCallForManyKeys() {
        var provider = FakeProvider.answering(Source.BIOPORTAL,
                GO, CHEBI, new TermKey("go", "0000001"), new TermKey("go", "0000002"),
                new TermKey("go", "0000003"), new TermKey("go", "0000004"));
        var keys = List.of(GO, CHEBI, new TermKey("go", "0000001"), new TermKey("go", "0000002"),
                new TermKey("go", "0000003"), new TermKey("go", "0000004"));
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "no page"));

        var result = resolver.resolve(keys, ResolverMode.PROVIDERS, false);

        assertEquals(1, provider.calls.get());
        assertEquals(keys.size(), provider.requested.getFirst().size());
        assertEquals(1, result.summary().providerRequests());
        assertEquals(keys.size(), result.summary().termsRequested());
    }

    // ---- 9. no provider supports it and the page fails ---------------------------------------

    @Test
    void unsupportedKeyWithFailedPageIsDefinitivelyUnresolved() {
        var urls = new ArrayList<String>();
        var resolver = resolver(List.of(), url -> {
            urls.add(url);
            return AnnotationFetchResult.failure(url, "http status 404");
        });

        var result = resolver.resolve(List.of(CHEBI), ResolverMode.DIRECT, false);

        assertEquals(Status.UNRESOLVED, result.definition(CHEBI).status());
        assertEquals(FailureClass.TRANSIENT,
                result.definition(CHEBI).failureClass(), "a failed fetch is transient");
        assertTrue(result.definition(CHEBI).failureReason().contains("404"));

        var noPage = resolver(List.of(), url -> new AnnotationFetchResult(url, 200, "text/plain", "   ", null))
                .resolve(List.of(CHEBI), ResolverMode.DIRECT, false);
        assertEquals(FailureClass.TRANSIENT, noPage.definition(CHEBI).failureClass());
    }

    @Test
    void definitiveAndRetriableOutcomesAreClassifiedDistinctly() {
        var blank = resolver(List.of(), url -> new AnnotationFetchResult(url, 200, "text/html", "<html></html>", null))
                .resolve(List.of(CHEBI), ResolverMode.DIRECT, false);
        assertEquals(FailureClass.TRANSIENT, blank.definition(CHEBI).failureClass());

        // A successful fetch is the contrast: resolved, page-sourced, no failure class at all.
        var fetched = resolver(List.of(), url -> new AnnotationFetchResult(url, 200, "text/plain", "text", null))
                .resolve(List.of(CHEBI, CHEBI_2), ResolverMode.DIRECT, false);
        assertTrue(fetched.resolved(CHEBI), "a successful fetch resolves the term");
        assertEquals(Status.RESOLVED, fetched.definition(CHEBI).status());
        assertEquals(Source.PAGE, fetched.definition(CHEBI).source());
        assertFalse(blank.resolved(CHEBI));
    }

    // ---- 10. the page fallback ----------------------------------------------------------------

    @Test
    void pageFallbackUsesTheCanonicalUrlExtractsTextAndCapsIt() {
        var urls = new ArrayList<String>();
        var resolver = resolver(List.of(), url -> {
            urls.add(url);
            return new AnnotationFetchResult(url, 200, "text/html", "<h1>Igneous activity</h1>", null);
        });

        var result = resolver.resolve(List.of(CHEBI), ResolverMode.DIRECT, false);

        assertEquals(List.of("https://identifiers.org/CHEBI:15377"), urls);
        var definition = result.definition(CHEBI);
        assertTrue(definition.resolved());
        assertEquals(Source.PAGE, definition.source());
        assertEquals("Igneous activity", definition.keywordText());
    }

    @Test
    void pageTextIsCappedAtTheConfiguredMaximum() {
        var capped = new AnnotationTermResolver(
                catalogue, List.of(), cache, Clock.fixed(NOW, ZoneOffset.UTC), TTL,
                url -> new AnnotationFetchResult(url, 200, "text/plain", "abcdefghij", null), extractor, 4);

        var result = capped.resolve(List.of(CHEBI), ResolverMode.DIRECT, false);

        assertEquals("abcd", result.definition(CHEBI).keywordText());
    }

    @Test
    void blankPageTextIsTransient() {
        var result = resolver(List.of(), url -> new AnnotationFetchResult(url, 200, "text/html", "<html> </html>", null))
                .resolve(List.of(CHEBI), ResolverMode.DIRECT, false);

        assertEquals(FailureClass.TRANSIENT, result.definition(CHEBI).failureClass());
        assertFalse(result.resolved(CHEBI));
    }

    @Test
    void aRunWithoutATextSourceForThePrefixSaysSoAndCachesNothing() {
        // Direct mode configures no provider. GO, FMA, SBO and KiSAO have no page text either, so the
        // honest reason is about the configuration, not a claim that the term publishes nothing.
        // Measured 2026-11-16: FMA's definitions live at BioPortal only; OLS4's FMA config indexes
        // none, and the four pages (403, SPA shell, resource notice) yield no entity text.
        var go = new TermKey("go", "0008150");
        var fma = new TermKey("fma", "62387");
        var sbo = new TermKey("sbo", "0000323");
        var kisao = new TermKey("kisao", "0000019");
        var fetcher = new AtomicInteger();
        var result = resolver(List.of(), url -> {
            fetcher.incrementAndGet();
            return new AnnotationFetchResult(url, 200, "text/plain", "page " + url, null);
        }).resolve(List.of(go, fma, sbo, kisao), ResolverMode.DIRECT, false);

        assertEquals(0, fetcher.get(), "a page that yields no entity text is never asked for");
        assertEquals(0, result.summary().pageRequests(), "no page was read, so no page request is counted");
        assertEquals(4, result.summary().unresolvedTerms());
        for (var key : List.of(go, fma, sbo, kisao)) {
            var definition = result.definition(key);
            assertEquals(Status.UNRESOLVED, definition.status(), key.wire());
            assertEquals(FailureClass.TRANSIENT, definition.failureClass(),
                    key.wire() + ": a missing source is a configuration fact, retried next run");
            assertTrue(definition.failureReason().contains("no text source configured for this prefix"),
                    key.wire() + ": " + definition.failureReason());
        }
        assertTrue(cache.saves.isEmpty(),
                "nothing was learned about the terms, so no configuration fact becomes cached knowledge");
    }

    @Test
    void aPrefixWhoseOnlyTextSourceIsNotConfiguredSaysSoInProvidersMode() {
        // FMA's declared definition source is BioPortal alone. This run configures OLS4, which the
        // adapter also declines for FMA; the fallback must not call that "no page text".
        var fma = new TermKey("fma", "62387");
        var provider = FakeProvider.answering(Source.OLS4);
        provider.omit.add(fma);
        var fetcher = new AtomicInteger();

        var result = resolver(List.of(provider), url -> {
            fetcher.incrementAndGet();
            return new AnnotationFetchResult(url, 200, "text/plain", "page " + url, null);
        }).resolve(List.of(fma), ResolverMode.PROVIDERS, false);

        var definition = result.definition(fma);
        assertEquals(0, fetcher.get(), "the FMA page is an OLS4 SPA shell and is never fetched");
        assertEquals(FailureClass.TRANSIENT, definition.failureClass());
        assertTrue(definition.failureReason().contains("no text source configured for this prefix"),
                definition.failureReason());
        assertTrue(cache.saves.isEmpty(), "the missing BioPortal key is not a fact about the term");
    }

    @Test
    void aPageThatYieldsNoTextIsDefinitiveWhenItsPrefixHasAConfiguredSource() {
        var go = new TermKey("go", "0008150");
        var provider = FakeProvider.answering(Source.OLS4);
        provider.omit.add(go);
        var fetcher = new AtomicInteger();

        var result = resolver(List.of(provider), url -> {
            fetcher.incrementAndGet();
            return new AnnotationFetchResult(url, 200, "text/plain", "page " + url, null);
        }).resolve(List.of(go), ResolverMode.PROVIDERS, false);

        var definition = result.definition(go);
        assertEquals(0, fetcher.get(), "a page that has no text is not asked for");
        assertEquals(FailureClass.DEFINITIVE, definition.failureClass(),
                "the source was configured and answered nothing, so the page answer is knowledge");
        assertTrue(definition.failureReason().contains("no page text for this prefix"),
                definition.failureReason());
        assertEquals(1, cache.savedByKey().size(), "a definitive negative is cached for the TTL");
    }

    // ---- 11. non-ontological keys are ignored -------------------------------------------------

    @Test
    void nonOntologicalKeysNeverEnterTheStage() {
        var kegg = new TermKey("kegg", "hsa00010");
        var provider = FakeProvider.answering(Source.BIOPORTAL, kegg);
        var fetcher = new AtomicInteger();
        var resolver = resolver(List.of(provider), url -> {
            fetcher.incrementAndGet();
            return AnnotationFetchResult.failure(url, "unexpected");
        });

        var result = resolver.resolve(List.of(kegg), ResolverMode.PROVIDERS, false);

        assertTrue(result.definitions().isEmpty(), "no definition for a non-ontological key");
        assertEquals(0, provider.calls.get());
        assertEquals(0, fetcher.get());
        assertTrue(cache.saves.isEmpty());
        assertEquals(0, result.summary().termsRequested());
    }

    // ---- 12. one cache write, knowledge only --------------------------------------------------

    @Test
    void cacheIsWrittenOnceWithResolvedAndDefinitiveResults() {
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        provider.answerUnresolved(CHEBI, FailureClass.DEFINITIVE, "no such class");
        provider.answerUnresolved(KISAO, FailureClass.TRANSIENT, "timeout");
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"));

        resolver.resolve(List.of(GO, CHEBI, KISAO), ResolverMode.PROVIDERS, false);

        assertEquals(1, cache.saves.size(), "one cache write per call");
        var written = cache.savedByKey();
        assertEquals(Status.RESOLVED, written.get(GO).definition().status());
        assertEquals(FailureClass.DEFINITIVE, written.get(CHEBI).definition().failureClass());
        assertEquals(FailureClass.TRANSIENT, written.get(KISAO).definition().failureClass());
        assertEquals(NOW, written.get(GO).fetchedAt());
    }

    @Test
    void emptyTextOrFailedFetchNeverBecomesAFreshEntry() {
        var empty = resolver(List.of(), url -> new AnnotationFetchResult(url, 200, "text/plain", "  ", null));
        empty.resolve(List.of(CHEBI), ResolverMode.DIRECT, false);
        var emptySaved = cache.savedByKey().get(CHEBI);
        assertTrue(emptySaved == null || !emptySaved.definition().resolved(), "a blank extraction is not knowledge");

        cache.clear();
        var failed = resolver(List.of(), url -> AnnotationFetchResult.failure(url, "http status 500"));
        var result = failed.resolve(List.of(CHEBI), ResolverMode.DIRECT, false);
        var saved = cache.savedByKey().get(CHEBI);
        assertTrue(saved == null || saved.definition().failureClass() == FailureClass.TRANSIENT);
        assertEquals(FailureClass.TRANSIENT, result.definition(CHEBI).failureClass());
    }

    // ---- 13. TTL boundary with a fixed clock --------------------------------------------------

    @Test
    void ttlBoundaryIsDecidedByTheFixedClock() {
        var exactlyAtTtl = NOW.minus(TTL);
        cache.put(Source.BIOPORTAL, GO, "cached GO text", exactlyAtTtl);
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);

        var atBoundary = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"))
                .resolve(List.of(GO), ResolverMode.PROVIDERS, false);

        assertEquals(1, atBoundary.summary().cacheMisses(), "an entry fetched exactly one TTL ago is stale");
        assertEquals(1, provider.calls.get());

        cache.clear();
        cache.put(Source.BIOPORTAL, GO, "cached GO text", exactlyAtTtl.plusSeconds(1));
        provider.calls.set(0);
        var justInside = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"))
                .resolve(List.of(GO), ResolverMode.PROVIDERS, false);

        assertEquals(1, justInside.summary().cacheHits(), "one second inside the TTL is fresh");
        assertEquals(0, provider.calls.get());
    }

    // ---- 14. nothing throws --------------------------------------------------------------------

    @Test
    void aThrowingProviderAndAThrowingFetcherBecomePerKeyUnresolvedEntries() {
        // CHEBI, not GO: GO's page yields no text and is never fetched, so a throwing fetcher could
        // not reach it and this test would pass without exercising the throw.
        var throwingProvider = new ThrowingProvider(Source.BIOPORTAL);
        var providerRun = resolver(List.of(throwingProvider), url -> AnnotationFetchResult.failure(url, "no page"))
                .resolve(List.of(CHEBI), ResolverMode.PROVIDERS, false);
        assertEquals(Status.UNRESOLVED, providerRun.definition(CHEBI).status());
        assertEquals(FailureClass.TRANSIENT, providerRun.definition(CHEBI).failureClass());

        var throwingFetcher = resolver(List.of(), url -> {
            throw new IllegalStateException("socket closed");
        });
        var directRun = throwingFetcher.resolve(List.of(CHEBI), ResolverMode.DIRECT, false);
        assertEquals(Status.UNRESOLVED, directRun.definition(CHEBI).status());
        assertTrue(directRun.definition(CHEBI).failureReason().contains("socket closed"));

        var offRun = throwingFetcher.resolve(List.of(CHEBI), ResolverMode.OFF, false);
        assertNotNull(offRun.definition(CHEBI));
    }

    @Test
    void nullAndDuplicateKeysAreDropped() {
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO);
        var result = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "unexpected"))
                .resolve(java.util.Arrays.asList(GO, null, GO), ResolverMode.PROVIDERS, false);

        assertEquals(1, result.definitions().size());
        assertEquals(1, result.summary().termsRequested());
        assertEquals(1, provider.requested.getFirst().size());
    }

    @Test
    void aKeyAProviderAnsweredDefinitivelyIsNotAskedOfThePageAgain() {
        var answered = new TermKey("chebi", "15377");
        var unclaimed = new TermKey("chebi", "16526");
        var provider = new FakeProvider(Source.OLS4, key -> key.prefix().equals("chebi"));
        provider.answerUnresolved(answered, FailureClass.DEFINITIVE, "no such class");
        provider.omit.add(unclaimed);
        var urls = new ArrayList<String>();
        var resolver = resolver(List.of(provider), url -> {
            urls.add(url);
            return AnnotationFetchResult.failure(url, "no page");
        });

        var result = resolver.resolve(List.of(answered, unclaimed), ResolverMode.PROVIDERS, false);

        assertEquals(1, urls.size(), "only the unclaimed key reaches the page: " + urls);
        assertTrue(urls.get(0).contains("16526"), urls.get(0));
        assertEquals(FailureClass.DEFINITIVE, result.definition(answered).failureClass(),
                "the provider's definitive answer is what the run records");
        assertEquals(2, provider.supportCalls.get(), "one supports() answer per key for the run");
    }

    @Test
    void aProviderIsAskedAboutEachKeyAtMostOncePerRun() {
        var provider = FakeProvider.answering(Source.BIOPORTAL, GO, CHEBI);
        var resolver = resolver(List.of(provider), url -> AnnotationFetchResult.failure(url, "no page"));

        resolver.resolve(List.of(GO, CHEBI), ResolverMode.PROVIDERS, false);

        assertEquals(2, provider.supportCalls.get(), "one supports() answer per key, never one per pass");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private AnnotationTermResolver resolver(List<AnnotationTermProvider> providers, AnnotationResourceFetcher fetcher) {
        return new AnnotationTermResolver(
                catalogue,
                providers,
                cache,
                Clock.fixed(NOW, ZoneOffset.UTC),
                TTL,
                fetcher,
                extractor,
                AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
    }

    /** A cache that records every write, so "written once" is assertable. */
    private static final class RecordingCache implements AnnotationTermCache {

        private final Map<TermKey, CachedTerm> entries = new LinkedHashMap<>();
        private final List<List<CachedTerm>> saves = new ArrayList<>();
        private boolean failFindAll;
        private boolean failSaveAll;

        @Override
        public Map<TermKey, CachedTerm> findAll(Collection<TermKey> keys) {
            if (failFindAll) {
                throw new IllegalStateException("cache unavailable");
            }
            var found = new LinkedHashMap<TermKey, CachedTerm>();
            for (var key : keys) {
                var entry = entries.get(key);
                if (entry != null) {
                    found.put(key, entry);
                }
            }
            return found;
        }

        @Override
        public void saveAll(Collection<CachedTerm> terms) {
            if (failSaveAll) {
                throw new IllegalStateException("cache unavailable");
            }
            var copy = List.copyOf(terms);
            saves.add(copy);
            for (var term : copy) {
                entries.put(term.definition().key(), term);
            }
        }

        void put(Source source, TermKey key, String text, Instant fetchedAt) {
            entries.put(key, new CachedTerm(TermDefinition.resolved(key, text, source), fetchedAt));
        }

        void clear() {
            entries.clear();
            saves.clear();
        }

        Map<TermKey, CachedTerm> savedByKey() {
            var written = new LinkedHashMap<TermKey, CachedTerm>();
            for (var batch : saves) {
                for (var term : batch) {
                    written.put(term.definition().key(), term);
                }
            }
            return written;
        }
    }

    /** A provider that answers a fixed map, records each batch, and can be made to fail. */
    private static final class FakeProvider implements AnnotationTermProvider {

        private final Source source;
        private final java.util.function.Predicate<TermKey> support;
        private final Map<TermKey, TermDefinition> answered = new LinkedHashMap<>();
        private final List<List<TermKey>> requested = new ArrayList<>();
        private final java.util.Set<TermKey> omit = new java.util.LinkedHashSet<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger supportCalls = new AtomicInteger();
        private RuntimeException failWith;

        FakeProvider(Source source, java.util.function.Predicate<TermKey> support) {
            this.source = source;
            this.support = support;
        }

        static FakeProvider answering(Source source, TermKey... keys) {
            var provider = new FakeProvider(source, key -> true);
            for (var key : keys) {
                provider.answered.put(key, TermDefinition.resolved(key, "provider GO text", source));
            }
            return provider;
        }

        void answerUnresolved(TermKey key, FailureClass failureClass, String reason) {
            answered.put(key, TermDefinition.unresolved(key, failureClass, reason));
        }

        @Override
        public Source source() {
            return source;
        }

        @Override
        public boolean supports(TermKey key) {
            supportCalls.incrementAndGet();
            return support.test(key);
        }

        @Override
        public Map<TermKey, TermDefinition> resolveAll(Collection<TermKey> keys) {
            calls.incrementAndGet();
            requested.add(List.copyOf(keys));
            if (failWith != null) {
                throw failWith;
            }
            var batch = new LinkedHashMap<TermKey, TermDefinition>();
            for (var key : keys) {
                var explicit = answered.get(key);
                var definition = explicit != null
                        ? explicit
                        : TermDefinition.resolved(key, "provider GO text", source);
                if (!omit.contains(key)) {
                    batch.put(key, definition);
                }
            }
            return batch;
        }
    }

    /** A provider that throws from every method — the proof that a mode never touches it. */
    private static final class ThrowingProvider implements AnnotationTermProvider {

        private final Source source;
        private final AtomicInteger calls = new AtomicInteger();

        ThrowingProvider(Source source) {
            this.source = source;
        }

        @Override
        public Source source() {
            return source;
        }

        @Override
        public boolean supports(TermKey key) {
            throw new IllegalStateException("supports must not be called");
        }

        @Override
        public Map<TermKey, TermDefinition> resolveAll(Collection<TermKey> keys) {
            calls.incrementAndGet();
            throw new IllegalStateException("provider down");
        }
    }
}

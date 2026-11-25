package org.masymos.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.masymos.core.annotation.AnnotationPrefixCatalogue;
import org.masymos.core.annotation.AnnotationTermCache;
import org.masymos.core.annotation.AnnotationTermProvider;
import org.masymos.core.annotation.AnnotationTermResolver;
import org.masymos.core.annotation.AnnotationTextExtractor;
import org.masymos.core.annotation.QuickGoTermProvider;
import org.masymos.core.annotation.ResolverMode;
import org.masymos.core.annotation.TermDefinition;
import org.masymos.core.annotation.TermKey;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/**
 * Proves where QuickGO sits in the configured chain and how availability reports it (ANNO-1g).
 *
 * <p>The chain order is asserted, not assumed: all three providers support {@code go}, so only a
 * QuickGO that leads the chain can be the source of GO text. The last test closes the loop by running
 * the configured chain through the resolver.
 */
class AnnotationProviderConfigurationTest {

    private static final String BIOPORTAL = "https://bioportal.example.test";
    private static final String BIOPORTAL_KEY = "test-key";
    private static final String OLS = "https://ols.example.test/api";
    private static final String QUICKGO = "https://quickgo.example.test";
    private static final TermKey GO = new TermKey("go", "0008150");
    private static final TermKey CHEBI = new TermKey("chebi", "15377");
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final String CHAIN = "annotationTermProviders";
    private static final String AVAILABILITY = "annotationProviderAvailability";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(AnnotationProviderConfiguration.class);

    @Test
    void quickGoLeadsTheChainAsTheFirstProviderServingTheGoPrefix() {
        withAllProviders().run(context -> {
            List<AnnotationTermProvider> chain = chainOf(context);

            assertThat(chain).hasSize(3);
            assertThat(chain).extracting(AnnotationTermProvider::source)
                    .containsExactly(
                            TermDefinition.Source.QUICKGO,
                            TermDefinition.Source.BIOPORTAL,
                            TermDefinition.Source.OLS4);
            assertThat(chain.getFirst()).isInstanceOf(QuickGoTermProvider.class);
            assertThat(chain).filteredOn(provider -> provider.supports(GO)).first()
                    .isInstanceOf(QuickGoTermProvider.class);
        });
    }

    @Test
    void quickGoDefaultsToThePublicBaseUrl() {
        runner.run(context -> {
            List<AnnotationTermProvider> chain = chainOf(context);

            assertThat(chain).singleElement().isInstanceOf(QuickGoTermProvider.class);
            assertThat(((QuickGoTermProvider) chain.getFirst()).batchUrl(List.of("GO:0008150")))
                    .isEqualTo(AnnotationProviderConfiguration.DEFAULT_QUICKGO_BASE_URL
                            + "/services/ontology/go/terms/GO:0008150");
        });
    }

    @Test
    void availabilityReportsQuickGoAvailableWhenSet() {
        runner.withPropertyValues("masymos.annotations.providers.quickgo.base-url=" + QUICKGO)
                .run(context -> {
                    var availability = context.getBean(
                            AVAILABILITY, AnnotationProviderConfiguration.AnnotationProviderAvailability.class);

                    assertThat(availability.available()).containsExactly("quickgo");
                    assertThat(availability.missingVariables())
                            .containsExactly(
                                    AnnotationProviderConfiguration.BIOPORTAL_BASE_URL_VARIABLE,
                                    AnnotationProviderConfiguration.BIOPORTAL_API_KEY_VARIABLE,
                                    AnnotationProviderConfiguration.OLS_BASE_URL_VARIABLE);
                });
    }

    @Test
    void availabilityNamesTheQuickGoVariableWhenBlank() {
        runner.withPropertyValues("masymos.annotations.providers.quickgo.base-url= ")
                .run(context -> {
                    var availability = context.getBean(
                            AVAILABILITY, AnnotationProviderConfiguration.AnnotationProviderAvailability.class);

                    assertThat(availability.available()).doesNotContain("quickgo");
                    assertThat(availability.missingVariables())
                            .contains(AnnotationProviderConfiguration.QUICKGO_BASE_URL_VARIABLE);
                });
    }

    @Test
    void theConfiguredChainResolvesGoFromQuickGoInProvidersMode() {
        withAllProviders().run(context -> {
            // Each adapter is wrapped so this test never touches the network: every provider answers
            // whatever the chain asks in its own source. Chain order and support are the real thing.
            List<AnnotationTermProvider> offline = chainOf(context).stream()
                    .map(provider -> (AnnotationTermProvider) new Offline(provider))
                    .toList();
            var resolver = new AnnotationTermResolver(
                    AnnotationPrefixCatalogue.standard(),
                    offline,
                    new EmptyCache(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    AnnotationTermResolver.DEFAULT_TTL,
                    url -> {
                        throw new AssertionError("page fetcher must not be reached: " + url);
                    },
                    new AnnotationTextExtractor(),
                    AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);

            var result = resolver.resolve(List.of(GO, CHEBI), ResolverMode.PROVIDERS, false);

            assertThat(result.resolved(GO)).isTrue();
            assertThat(result.definition(GO).source()).isEqualTo(TermDefinition.Source.QUICKGO);
            assertThat(result.definition(CHEBI).source()).isEqualTo(TermDefinition.Source.BIOPORTAL);
        });
    }

    private ApplicationContextRunner withAllProviders() {
        return runner.withPropertyValues(
                "masymos.annotations.providers.quickgo.base-url=" + QUICKGO,
                "masymos.annotations.providers.bioportal.base-url=" + BIOPORTAL,
                "masymos.annotations.providers.bioportal.api-key=" + BIOPORTAL_KEY,
                "masymos.annotations.providers.ols.base-url=" + OLS);
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationTermProvider> chainOf(ApplicationContext context) {
        return (List<AnnotationTermProvider>) context.getBean(CHAIN);
    }

    /** Answers every accepted key in the wrapped provider's source, without any I/O. */
    private record Offline(AnnotationTermProvider delegate) implements AnnotationTermProvider {

        @Override
        public TermDefinition.Source source() {
            return delegate.source();
        }

        @Override
        public boolean supports(TermKey key) {
            return delegate.supports(key);
        }

        @Override
        public Map<TermKey, TermDefinition> resolveAll(Collection<TermKey> keys) {
            var batch = new LinkedHashMap<TermKey, TermDefinition>();
            for (var key : keys) {
                batch.put(key, TermDefinition.resolved(key, source() + " text", source()));
            }
            return batch;
        }
    }

    private static final class EmptyCache implements AnnotationTermCache {

        @Override
        public Map<TermKey, AnnotationTermCache.CachedTerm> findAll(Collection<TermKey> keys) {
            return Map.of();
        }

        @Override
        public void saveAll(Collection<AnnotationTermCache.CachedTerm> terms) {
        }
    }
}

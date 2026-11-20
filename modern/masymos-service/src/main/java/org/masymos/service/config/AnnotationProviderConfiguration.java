package org.masymos.service.config;

import java.util.ArrayList;
import java.util.List;
import org.masymos.core.annotation.AnnotationPrefixCatalogue;
import org.masymos.core.annotation.AnnotationProviderHttp;
import org.masymos.core.annotation.AnnotationTermProvider;
import org.masymos.core.annotation.AnnotationTextExtractor;
import org.masymos.core.annotation.BioPortalTermProvider;
import org.masymos.core.annotation.Ols4TermProvider;
import org.masymos.core.annotation.QuickGoTermProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The configured ontology term providers of an annotation run, in chain order.
 *
 * <p>Configuration is a service concern: the core resolver only knows the providers it is given, so
 * this class turns the configured properties into the ordered provider list and reports which
 * provider is unusable and why. It never fails startup on a missing provider — {@code providers}
 * without a BioPortal key runs OLS4-only and says so (design answer 5), and a run with no usable
 * provider is refused at the surface, not here (design answer 12).
 *
 * <p><strong>Chain order is load-bearing.</strong> The resolver asks the providers in chain order and
 * removes a key once a supporting provider answers it. All three providers support {@code go}, so
 * QuickGO is placed <em>first</em>: only then does GO text come from QuickGO instead of BioPortal or
 * OLS4. QuickGO supports no other prefix, so the order of the other two is unaffected.
 *
 * <p>Availability rules:
 *
 * <ul>
 *   <li>QuickGO needs no key and is available when its base URL is set. It is the GO provider
 *       (ANNO-1g), so it leads the chain.
 *   <li>BioPortal is available only when <em>both</em> its base URL and its API key are set. A key
 *       without a base URL is "not configured" and is named the same way.
 *   <li>OLS4 needs no key and is available when its base URL is set.
 * </ul>
 *
 * <p>The API key is read here and handed to {@link BioPortalTermProvider}, which sends it as an
 * {@code Authorization: apikey …} header; it is never placed in a URL, a log line or an exception.
 */
@Configuration
public class AnnotationProviderConfiguration {

    /** Environment variable naming the BioPortal base URL. */
    public static final String BIOPORTAL_BASE_URL_VARIABLE = "BIOPORTAL_BASE_URL";

    /** Environment variable naming the BioPortal API key. */
    public static final String BIOPORTAL_API_KEY_VARIABLE = "BIOPORTAL_API_KEY";

    /** Environment variable naming the OLS4 base URL. */
    public static final String OLS_BASE_URL_VARIABLE = "OLS_BASE_URL";

    /** Environment variable naming the QuickGO base URL. */
    public static final String QUICKGO_BASE_URL_VARIABLE = "QUICKGO_BASE_URL";

    /** The public QuickGO base URL: the default of {@code masymos.annotations.providers.quickgo.base-url}. */
    public static final String DEFAULT_QUICKGO_BASE_URL = "https://www.ebi.ac.uk/QuickGO";

    /** The design's default chunk size for one BioPortal batch request. */
    public static final int DEFAULT_MAX_BATCH = BioPortalTermProvider.DEFAULT_MAX_BATCH;

    /**
     * The ordered provider chain: QuickGO first (the GO provider), BioPortal second, OLS4 third, empty
     * when none is usable.
     *
     * @param quickgoBaseUrl {@code masymos.annotations.providers.quickgo.base-url}; blank when unset
     * @param bioportalBaseUrl {@code masymos.annotations.providers.bioportal.base-url}; blank when unset
     * @param bioportalApiKey {@code masymos.annotations.providers.bioportal.api-key}; blank when unset
     * @param olsBaseUrl {@code masymos.annotations.providers.ols.base-url}; blank when unset
     * @param maxBatch chunk size passed to the BioPortal provider; values below 1 fall back to the default
     */
    @Bean
    public List<AnnotationTermProvider> annotationTermProviders(
            @Value("${masymos.annotations.providers.quickgo.base-url:" + DEFAULT_QUICKGO_BASE_URL + "}")
                    String quickgoBaseUrl,
            @Value("${masymos.annotations.providers.bioportal.base-url:}") String bioportalBaseUrl,
            @Value("${masymos.annotations.providers.bioportal.api-key:}") String bioportalApiKey,
            @Value("${masymos.annotations.providers.ols.base-url:}") String olsBaseUrl,
            @Value("${masymos.annotations.providers.batch.max:" + DEFAULT_MAX_BATCH + "}") int maxBatch
    ) {
        var providers = new ArrayList<AnnotationTermProvider>();
        var catalogue = AnnotationPrefixCatalogue.standard();
        var http = AnnotationProviderHttp.jdk();
        var maxTextLength = AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH;
        var chunkSize = maxBatch < 1 ? DEFAULT_MAX_BATCH : maxBatch;

        // QuickGO leads the chain on purpose: BioPortal and OLS4 also serve "go", and the resolver
        // stops at the first supporting provider that answers, so a later QuickGO would never see GO.
        if (isSet(quickgoBaseUrl)) {
            providers.add(new QuickGoTermProvider(quickgoBaseUrl.trim(), http, maxTextLength));
        }
        if (isSet(bioportalBaseUrl) && isSet(bioportalApiKey)) {
            providers.add(new BioPortalTermProvider(
                    bioportalBaseUrl.trim(),
                    bioportalApiKey.trim(),
                    chunkSize,
                    http,
                    catalogue,
                    maxTextLength));
        }
        if (isSet(olsBaseUrl)) {
            providers.add(new Ols4TermProvider(olsBaseUrl.trim(), http, catalogue, maxTextLength));
        }
        return List.copyOf(providers);
    }

    /**
     * The same inputs rendered as the run-facing availability report: the sources that could answer and
     * the environment variable of every provider that cannot.
     */
    @Bean
    public AnnotationProviderAvailability annotationProviderAvailability(
            @Value("${masymos.annotations.providers.quickgo.base-url:" + DEFAULT_QUICKGO_BASE_URL + "}")
                    String quickgoBaseUrl,
            @Value("${masymos.annotations.providers.bioportal.base-url:}") String bioportalBaseUrl,
            @Value("${masymos.annotations.providers.bioportal.api-key:}") String bioportalApiKey,
            @Value("${masymos.annotations.providers.ols.base-url:}") String olsBaseUrl
    ) {
        var available = new ArrayList<String>();
        var missing = new ArrayList<String>();

        if (isSet(quickgoBaseUrl)) {
            available.add("quickgo");
        } else {
            missing.add(QUICKGO_BASE_URL_VARIABLE);
        }
        if (isSet(bioportalBaseUrl) && isSet(bioportalApiKey)) {
            available.add("bioportal");
        } else {
            // A key without a base URL is named the same way: the pair is what makes the provider usable.
            if (!isSet(bioportalBaseUrl)) {
                missing.add(BIOPORTAL_BASE_URL_VARIABLE);
            }
            if (!isSet(bioportalApiKey)) {
                missing.add(BIOPORTAL_API_KEY_VARIABLE);
            }
        }
        if (isSet(olsBaseUrl)) {
            available.add("ols4");
        } else {
            missing.add(OLS_BASE_URL_VARIABLE);
        }
        return new AnnotationProviderAvailability(
                List.copyOf(available), List.copyOf(missing));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * What a run can rely on: the provider sources that are configured, in chain order, and the
     * environment variable of each provider that is not — so a refusal can name the variable to set
     * instead of saying "not configured".
     */
    public record AnnotationProviderAvailability(List<String> available, List<String> missingVariables) {

        public AnnotationProviderAvailability {
            available = available == null ? List.of() : List.copyOf(available);
            missingVariables = missingVariables == null ? List.of() : List.copyOf(missingVariables);
        }

        /** True when at least one provider can answer, which is what a {@code providers} run needs. */
        public boolean anyAvailable() {
            return !available.isEmpty();
        }
    }
}

package org.masymos.service.api;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.masymos.core.annotation.ResolverMode;
import org.masymos.core.model.AnnotationIndexingOptions;
import org.masymos.core.model.AnnotationIndexingResult;
import org.masymos.core.model.AnnotationIndexingResult.TermResolutionSummary;
import org.masymos.core.spi.AnnotationIndexService;
import org.masymos.service.config.AnnotationProviderConfiguration;
import org.masymos.service.config.AnnotationProviderConfiguration.AnnotationProviderAvailability;
import org.springframework.test.web.servlet.ResultActions;

class AnnotationMaintenanceControllerTest {
    private static final AnnotationProviderAvailability NO_PROVIDERS = new AnnotationProviderAvailability(
            List.of(),
            List.of(
                    AnnotationProviderConfiguration.BIOPORTAL_BASE_URL_VARIABLE,
                    AnnotationProviderConfiguration.BIOPORTAL_API_KEY_VARIABLE,
                    AnnotationProviderConfiguration.OLS_BASE_URL_VARIABLE));

    private static final AnnotationProviderAvailability ONE_PROVIDER = new AnnotationProviderAvailability(
            List.of("ols4"),
            List.of(
                    AnnotationProviderConfiguration.BIOPORTAL_BASE_URL_VARIABLE,
                    AnnotationProviderConfiguration.BIOPORTAL_API_KEY_VARIABLE));

    @Test
    void rebuildsAnnotationIndexExplicitly() throws Exception {
        var controller = controller(options -> new AnnotationIndexingResult(4, 2, 1, 1), ONE_PROVIDER);

        perform(controller, "dropExistingIndex", "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scannedResources").value(4))
                .andExpect(jsonPath("$.indexedResources").value(2))
                .andExpect(jsonPath("$.skippedResources").value(1))
                .andExpect(jsonPath("$.failedResources").value(1));
    }

    @Test
    void passesBoundedRunOptionsToTheService() throws Exception {
        var captured = new AtomicReference<AnnotationIndexingOptions>();
        var controller = controller(options -> {
            captured.set(options);
            return new AnnotationIndexingResult(0, 0, 0, 0);
        }, ONE_PROVIDER);

        perform(
                        controller,
                        "dropExistingIndex", "true",
                        "limit", "25",
                        "retryFailed", "true",
                        "maxConcurrency", "2")
                .andExpect(status().isOk());

        var options = captured.get();
        assertTrue(options.dropExistingIndex());
        assertEquals(25, options.limit());
        assertTrue(options.retryFailed());
        assertEquals(2, options.maxConcurrency());
    }

    @Test
    void appliesConservativeDefaults() throws Exception {
        var captured = new AtomicReference<AnnotationIndexingOptions>();
        var controller = controller(options -> {
            captured.set(options);
            return new AnnotationIndexingResult(0, 0, 0, 0);
        }, ONE_PROVIDER);

        perform(controller).andExpect(status().isOk());

        assertEquals(AnnotationIndexingOptions.defaults(), captured.get());
        assertEquals(ResolverMode.DIRECT, captured.get().resolverMode());
        assertFalse(captured.get().refreshTerms());
    }

    @Test
    void passesTheResolverModeAndRefreshFlagToTheService() throws Exception {
        var captured = new AtomicReference<AnnotationIndexingOptions>();
        var controller = controller(options -> {
            captured.set(options);
            return new AnnotationIndexingResult(0, 0, 0, 0);
        }, ONE_PROVIDER);

        perform(controller, "resolverMode", "providers", "refreshTerms", "true")
                .andExpect(status().isOk());

        assertEquals(ResolverMode.PROVIDERS, captured.get().resolverMode());
        assertTrue(captured.get().refreshTerms());
    }

    @Test
    void rejectsAnUnknownResolverMode() throws Exception {
        var called = new AtomicReference<Boolean>(false);
        var controller = controller(options -> {
            called.set(true);
            return new AnnotationIndexingResult(0, 0, 0, 0);
        }, ONE_PROVIDER);

        perform(controller, "resolverMode", "telepathy")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(allOf(
                                containsString("providers"),
                                containsString("direct"),
                                containsString("off"))));

        assertFalse(called.get(), "a refused run must not reach the service");
    }

    @Test
    void refusesProvidersWhenNoProviderIsConfigured() throws Exception {
        var called = new AtomicReference<Boolean>(false);
        var controller = controller(options -> {
            called.set(true);
            return new AnnotationIndexingResult(0, 0, 0, 0);
        }, NO_PROVIDERS);

        perform(controller, "resolverMode", "providers")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value(containsString(AnnotationProviderConfiguration.OLS_BASE_URL_VARIABLE)));

        assertFalse(called.get(), "a refused run must not reach the service");
    }

    @Test
    void refusesADropUnderOffMode() throws Exception {
        var called = new AtomicReference<Boolean>(false);
        var controller = controller(options -> {
            called.set(true);
            return new AnnotationIndexingResult(0, 0, 0, 0);
        }, ONE_PROVIDER);

        perform(controller, "dropExistingIndex", "true", "resolverMode", "off")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("term-backed text")));

        assertFalse(called.get(), "a refused run must not reach the service");
    }

    @Test
    void reportsTheTermCountersWhenTheRunMeasuredThem() throws Exception {
        var summary = new TermResolutionSummary(
                ResolverMode.PROVIDERS,
                List.of("bioportal", "ols4"),
                42,
                3,
                30,
                12,
                1,
                List.of("bioportal"),
                12,
                0);
        var controller = controller(
                options -> new AnnotationIndexingResult(4, 2, 1, 1, summary), ONE_PROVIDER);

        perform(controller, "resolverMode", "providers")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolverMode").value("providers"))
                .andExpect(jsonPath("$.providersAvailable[0]").value("bioportal"))
                .andExpect(jsonPath("$.providersAvailable[1]").value("ols4"))
                .andExpect(jsonPath("$.termsRequested").value(42))
                .andExpect(jsonPath("$.providerRequests").value(3))
                .andExpect(jsonPath("$.cacheHits").value(30))
                .andExpect(jsonPath("$.cacheMisses").value(12))
                .andExpect(jsonPath("$.unresolvedTerms").value(1))
                .andExpect(jsonPath("$.providersDemoted[0]").value("bioportal"))
                .andExpect(jsonPath("$.pageRequests").value(12))
                .andExpect(jsonPath("$.cacheFailures").value(0));
    }

    @Test
    void omitsTheTermCountersWhenTheRunDidNotMeasureThem() throws Exception {
        var controller = controller(options -> new AnnotationIndexingResult(4, 2, 1, 1), ONE_PROVIDER);

        perform(controller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolverMode").doesNotExist())
                .andExpect(jsonPath("$.providersAvailable").doesNotExist())
                .andExpect(jsonPath("$.termsRequested").doesNotExist())
                .andExpect(jsonPath("$.providerRequests").doesNotExist())
                .andExpect(jsonPath("$.cacheHits").doesNotExist())
                .andExpect(jsonPath("$.cacheMisses").doesNotExist())
                .andExpect(jsonPath("$.unresolvedTerms").doesNotExist())
                .andExpect(jsonPath("$.providersDemoted").doesNotExist())
                .andExpect(jsonPath("$.pageRequests").doesNotExist())
                .andExpect(jsonPath("$.cacheFailures").doesNotExist());
    }

    @Test
    void returnsStorageUnavailableWhenAnnotationIndexingIsDisabled() throws Exception {
        var controller = controller(new DisabledAnnotationIndexService(), ONE_PROVIDER);

        perform(controller)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Storage unavailable"));
    }

    private static AnnotationMaintenanceController controller(
            AnnotationIndexService service, AnnotationProviderAvailability availability) {
        return new AnnotationMaintenanceController(service, availability);
    }

    private static ResultActions perform(AnnotationMaintenanceController controller, String... params)
            throws Exception {
        var request = post("/api/v1/maintenance/annotations/index");
        for (var index = 0; index + 1 < params.length; index += 2) {
            request.param(params[index], params[index + 1]);
        }
        return standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(request);
    }

    private static final class DisabledAnnotationIndexService implements AnnotationIndexService {
        @Override
        public AnnotationIndexingResult rebuild(AnnotationIndexingOptions options) {
            throw new StorageUnavailableException("model storage is disabled");
        }
    }
}

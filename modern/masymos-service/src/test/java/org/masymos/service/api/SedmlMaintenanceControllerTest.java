package org.masymos.service.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.masymos.core.model.SedmlLinkingResult;
import org.masymos.core.spi.SedmlLinkingRepository;

class SedmlMaintenanceControllerTest {
    @Test
    void linksSedmlReferencesExplicitly() throws Exception {
        var controller = new SedmlMaintenanceController(() -> new SedmlLinkingResult(2, 3, 5, 1));

        standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/maintenance/sedml/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelReferenceLinks").value(2))
                .andExpect(jsonPath("$.sbmlObservationLinks").value(3))
                .andExpect(jsonPath("$.cellmlObservationLinks").value(5))
                .andExpect(jsonPath("$.changeLinks").value(1));
    }

    @Test
    void returnsStorageUnavailableWhenSedmlLinkingIsDisabled() throws Exception {
        var controller = new SedmlMaintenanceController(new DisabledSedmlLinkingRepository());

        standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/maintenance/sedml/links"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Storage unavailable"));
    }

    private static final class DisabledSedmlLinkingRepository implements SedmlLinkingRepository {
        @Override
        public SedmlLinkingResult linkSedmlReferences() {
            throw new StorageUnavailableException("model storage is disabled");
        }
    }
}

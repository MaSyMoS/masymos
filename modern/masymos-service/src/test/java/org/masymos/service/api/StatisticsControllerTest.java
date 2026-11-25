package org.masymos.service.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.masymos.core.model.StoreStatistics;
import org.masymos.core.spi.StatisticsRepository;
import org.springframework.test.web.servlet.MockMvc;

class StatisticsControllerTest {
    @Test
    void reportsTheCountsTheStoreGave() throws Exception {
        var statistics = new StoreStatistics(12, 9, 5, 2, 1, 1, 7, 3, 40, 2);

        mockMvc(() -> statistics)
                .perform(get("/api/v1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(12))
                .andExpect(jsonPath("$.sbmlModels").value(5))
                .andExpect(jsonPath("$.annotations").value(40))
                .andExpect(jsonPath("$.archives").value(2));
    }

    @Test
    void storageThatCannotAnswerBecomes503() throws Exception {
        var repository = (StatisticsRepository) () -> {
            throw new StorageUnavailableException("model storage is disabled");
        };

        standaloneSetup(new StatisticsController(repository))
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(get("/api/v1/stats"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://masymos.org/problems/storage-unavailable"));
    }

    private static MockMvc mockMvc(StatisticsRepository repository) {
        return standaloneSetup(new StatisticsController(repository)).build();
    }
}

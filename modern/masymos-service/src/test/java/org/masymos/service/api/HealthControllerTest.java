package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.springframework.test.web.servlet.MockMvc;

class HealthControllerTest {
    @Test
    void withoutAStoreHealthIsUpAndSaysStorageIsDisabled() throws Exception {
        mockMvc(new HealthController(Optional.empty()))
                .perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.storage").value("disabled"));
    }

    @Test
    void aReachableStoreIsReportedAsUp() throws Exception {
        mockMvc(new HealthController(Optional.of(mock(Driver.class))))
                .perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.storage").value("UP"));
    }

    @Test
    void anUnreachableStoreIsDownWith503() throws Exception {
        var driver = mock(Driver.class);
        doThrow(new ServiceUnavailableException("connection refused")).when(driver).verifyConnectivity();

        mockMvc(new HealthController(Optional.of(driver)))
                .perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.storage").value("DOWN"));
    }

    @Test
    void aStoreThatHangsIsDownWithinTheProbeBudget() throws Exception {
        var driver = mock(Driver.class);
        doAnswer(invocation -> {
                    Thread.sleep(2_000);
                    return null;
                })
                .when(driver)
                .verifyConnectivity();

        var mockMvc = mockMvc(new HealthController(Optional.of(driver), Duration.ofMillis(50)));

        var started = System.nanoTime();
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.storage").value("DOWN"));
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 1_500, "the probe must not wait for the driver: " + elapsedMillis + " ms");
        assertTrue(elapsedMillis < 400, "the probe must not wait for the driver: " + elapsedMillis + " ms");
    }

    private static MockMvc mockMvc(HealthController controller) {
        return standaloneSetup(controller).build();
    }
}

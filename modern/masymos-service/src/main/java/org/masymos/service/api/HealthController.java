package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.masymos.service.api.dto.HealthResponse;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and storage readiness in one endpoint.
 *
 * <p>The REST layer is what answers, so a failing store never makes the service unreachable: the body
 * says {@code storage: DOWN} and the status is 503. When no store is configured at all
 * ({@code masymos.neo4j.enabled=false}) the answer is {@code storage: disabled} with 200 — deliberately
 * not DOWN, because nothing is broken.</p>
 *
 * <p>The probe is bounded by {@link #probeBudget}, because the driver's own connection timeout is far
 * too long for a readiness probe: a store that black-holes a port must not hold the probe for 30
 * seconds. The probe runs on a virtual thread and is abandoned when the budget expires.</p>
 */
@RestController
@Tag(name = "Health", description = "Liveness endpoint of the MaSyMoS service.")
public class HealthController {
    private static final Duration DEFAULT_PROBE_BUDGET = Duration.ofSeconds(2);

    private final Optional<Driver> driver;
    private final Duration probeBudget;

    @Autowired
    public HealthController(Optional<Driver> driver) {
        this(driver, DEFAULT_PROBE_BUDGET);
    }

    HealthController(Optional<Driver> driver, Duration probeBudget) {
        this.driver = driver;
        this.probeBudget = probeBudget;
    }

    @Operation(
            summary = "Service health",
            description = "Probes the model store. `storage` is `disabled` when no store is configured, and"
                    + " `DOWN` with HTTP 503 when the store does not answer within the probe budget. The REST"
                    + " layer answers in every case.")
    @ApiResponse(responseCode = "200", description = "Service is up; the store answers or is disabled.")
    @ApiResponse(responseCode = "503", description = "The store did not answer within the probe budget.")
    @GetMapping("/api/v1/health")
    public ResponseEntity<HealthResponse> health() {
        if (driver.isEmpty()) {
            return ResponseEntity.ok(new HealthResponse("UP", "disabled"));
        }
        return probe(driver.get())
                ? ResponseEntity.ok(new HealthResponse("UP", "UP"))
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthResponse("DOWN", "DOWN"));
    }

    private boolean probe(Driver storage) {
        var reachable = new CompletableFuture<Boolean>();
        Thread.ofVirtual().start(() -> {
            try {
                storage.verifyConnectivity();
                reachable.complete(true);
            } catch (RuntimeException exception) {
                reachable.complete(false);
            }
        });
        try {
            return Boolean.TRUE.equals(reachable.get(probeBudget.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException exception) {
            return false;
        }
    }
}

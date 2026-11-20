package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.masymos.core.spi.UnitBackfill;
import org.masymos.core.units.UnitBackfillReport;
import org.springframework.test.web.servlet.ResultActions;

class UnitBackfillControllerTest {
    @Test
    void defaultsToADryRun() throws Exception {
        var seenApply = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var controller = controller(apply -> {
            calls.incrementAndGet();
            seenApply.set(apply);
            return UnitBackfillReport.empty(apply);
        });

        perform(controller).andExpect(status().isOk());

        assertFalse(seenApply.get(), "the default call must reach the port as a dry run");
        assertTrue(calls.get() > 0, "the port must be reached");
    }

    @Test
    void passesApplyTrueToThePort() throws Exception {
        var seenApply = new AtomicBoolean(false);
        var controller = controller(apply -> {
            seenApply.set(apply);
            return UnitBackfillReport.empty(apply);
        });

        perform(controller, "apply", "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        assertTrue(seenApply.get(), "apply=true must reach the port as a write run");
    }

    @Test
    void reportsTheCountersAndTheDerivedFields() throws Exception {
        var report = new UnitBackfillReport(true, 120, 18, 98, 2, 1, 1, 0, 40, 12, 6, 3, 0);
        var controller = controller(apply -> report);

        perform(controller, "apply", "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.documentsScanned").value(120))
                .andExpect(jsonPath("$.documentsBackfilled").value(18))
                .andExpect(jsonPath("$.documentsAlreadyComplete").value(98))
                .andExpect(jsonPath("$.skippedWithoutStoredDocument").value(2))
                .andExpect(jsonPath("$.skippedTruncatedDocument").value(1))
                .andExpect(jsonPath("$.skippedUnsupportedType").value(1))
                .andExpect(jsonPath("$.skippedUnparseable").value(0))
                .andExpect(jsonPath("$.unitsCreated").value(40))
                .andExpect(jsonPath("$.usesUnitCreated").value(12))
                .andExpect(jsonPath("$.hasUnitCreated").value(6))
                .andExpect(jsonPath("$.compositionCreated").value(3))
                .andExpect(jsonPath("$.relationshipsSkipped").value(0))
                .andExpect(jsonPath("$.writes").value(61))
                .andExpect(jsonPath("$.complete").value(false));
    }

    @Test
    void aCompleteDryRunReportsNoWrites() throws Exception {
        var report = new UnitBackfillReport(false, 5, 0, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        var controller = controller(apply -> report);

        perform(controller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.writes").value(0))
                .andExpect(jsonPath("$.complete").value(true));
    }

    @Test
    void returnsStorageUnavailableWhenStorageIsDisabled() throws Exception {
        var controller = controller(apply -> {
            throw new StorageUnavailableException("model storage is disabled");
        });

        perform(controller, "apply", "true")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Storage unavailable"));
    }

    private static UnitBackfillController controller(UnitBackfill unitBackfill) {
        return new UnitBackfillController(unitBackfill);
    }

    private static ResultActions perform(UnitBackfillController controller, String... params) throws Exception {
        var request = post("/api/v1/maintenance/units-backfill");
        for (var index = 0; index + 1 < params.length; index += 2) {
            request.param(params[index], params[index + 1]);
        }
        return standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(request);
    }
}

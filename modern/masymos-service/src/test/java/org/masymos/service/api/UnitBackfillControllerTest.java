package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.masymos.core.spi.UnitBackfill;
import org.masymos.core.units.UnitBackfillReport;
import org.springframework.test.web.servlet.ResultActions;

class UnitBackfillControllerTest {
    @Test
    void defaultsToADryRun() throws Exception {
        var seenApply = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var controller = controller((apply, limit, cursor) -> {
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
        var controller = controller((apply, limit, cursor) -> {
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
        var report = new UnitBackfillReport(true, 120, 0, 18, 98, 2, 1, 1, 0, 40, 12, 6, 3, 0, null, false);
        var controller = controller((apply, limit, cursor) -> report);

        perform(controller, "apply", "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.documentsScanned").value(120))
                .andExpect(jsonPath("$.documentsNotScanned").value(0))
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
        var report = new UnitBackfillReport(false, 5, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, false);
        var controller = controller((apply, limit, cursor) -> report);

        perform(controller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.writes").value(0))
                .andExpect(jsonPath("$.complete").value(true));
    }

    @Test
    void passesTheRunLimitToThePortAndDefaultsToFiveHundred() throws Exception {
        var seenLimit = new AtomicInteger(-1);
        var controller = controller((apply, limit, cursor) -> {
            seenLimit.set(limit);
            return UnitBackfillReport.empty(apply);
        });

        perform(controller).andExpect(status().isOk());
        assertEquals(500, seenLimit.get(), "an unbounded request defaults to the documented 500");

        perform(controller, "limit", "25").andExpect(status().isOk());
        assertEquals(25, seenLimit.get());

        perform(controller, "limit", "0").andExpect(status().isOk());
        assertEquals(0, seenLimit.get(), "0 means unbounded and must reach the port unchanged");
    }

    /**
     * {@code C04-2}: the page position is a request parameter and the position to continue from is a
     * response field, so a caller can sweep a corpus in bounded requests.
     */
    @Test
    void passesTheCursorToThePortAndReportsTheNextOne() throws Exception {
        var seenCursor = new AtomicReference<String>("not-reached");
        var controller = controller((apply, limit, cursor) -> {
            seenCursor.set(cursor);
            return new UnitBackfillReport(false, 1, 4, 1, 0, 0, 0, 0, 0, 2, 1, 1, 1, 0, "uid-7", true);
        });

        perform(controller, "cursor", "uid-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").value("uid-7"))
                .andExpect(jsonPath("$.startedFromCursor").value(true))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.documentsNotScanned").value(4));

        assertEquals("uid-3", seenCursor.get(), "the cursor must reach the port unchanged");
    }

    @Test
    void anAbsentCursorMeansTheBeginningOfThePopulation() throws Exception {
        var seenCursor = new AtomicReference<String>("not-reached");
        var controller = controller((apply, limit, cursor) -> {
            seenCursor.set(cursor);
            return UnitBackfillReport.empty(apply);
        });

        perform(controller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startedFromCursor").value(false))
                .andExpect(jsonPath("$.complete").value(true));

        assertNull(seenCursor.get(), "no cursor is passed as null, which the port reads as 'from the start'");
    }

    @Test
    void returnsStorageUnavailableWhenStorageIsDisabled() throws Exception {
        var controller = controller((apply, limit, cursor) -> {
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

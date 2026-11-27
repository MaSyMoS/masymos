package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.masymos.core.rederive.ReDeriveReport;
import org.masymos.core.spi.ReDerive;

class ReDeriveControllerTest {
    @Test
    void reportsTheScanTheShortfallAndTheCursor() throws Exception {
        var seenLimit = new AtomicInteger(-1);
        var seenCursor = new AtomicReference<>();
        var controller = new ReDeriveController(new ReDerive() {
            @Override
            public ReDeriveReport run(int limit, String cursor) {
                seenLimit.set(limit);
                seenCursor.set(cursor);
                return new ReDeriveReport(12, 9, 3, 1, 0, 0, 0, 4, Map.of("ANNOTATION", 4), 812, "abc", true);
            }

            @Override
            public ReDeriveReport apply(int limit, String cursor) {
                throw new AssertionError("a request without apply must not reach the repair");
            }
        });

        standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/maintenance/re-derive?limit=7&cursor=prev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsScanned").value(12))
                .andExpect(jsonPath("$.documentsStale").value(9))
                .andExpect(jsonPath("$.documentsCurrent").value(3))
                .andExpect(jsonPath("$.skippedTruncatedDocument").value(1))
                .andExpect(jsonPath("$.documentsNotScanned").value(4))
                .andExpect(jsonPath("$.missingDerivedKeys").value(812))
                .andExpect(jsonPath("$.missingByLabel.ANNOTATION").value(4))
                .andExpect(jsonPath("$.nextCursor").value("abc"))
                .andExpect(jsonPath("$.startedFromCursor").value(true))
                .andExpect(jsonPath("$.applied").value(0));

        assertEquals(7, seenLimit.get());
        assertEquals("prev", seenCursor.get());
    }

    /** {@code apply=true} is the repair: the request reaches the writer and the report names what it rewrote. */
    @Test
    void appliesWhenTheRequestAsksForRepair() throws Exception {
        var seenLimit = new AtomicInteger(-1);
        var seenCursor = new AtomicReference<>();
        var controller = new ReDeriveController(new ReDerive() {
            @Override
            public ReDeriveReport run(int limit, String cursor) {
                throw new AssertionError("apply=true must not fall back to the scan");
            }

            @Override
            public ReDeriveReport apply(int limit, String cursor) {
                seenLimit.set(limit);
                seenCursor.set(cursor);
                return new ReDeriveReport(12, 9, 3, 1, 0, 0, 0, 4, Map.of(), 0, "def", true, 9);
            }
        });

        standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/maintenance/re-derive?limit=7&cursor=prev&apply=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentsScanned").value(12))
                .andExpect(jsonPath("$.documentsStale").value(9))
                .andExpect(jsonPath("$.applied").value(9))
                .andExpect(jsonPath("$.nextCursor").value("def"));

        assertEquals(7, seenLimit.get());
        assertEquals("prev", seenCursor.get());
    }

    /** A scan is not a repair: a request without the flag never reaches the writer. */
    @Test
    void defaultsToTheDocumentedPageSize() throws Exception {
        var seenLimit = new AtomicInteger(-1);
        var controller = new ReDeriveController(new ReDerive() {
            @Override
            public ReDeriveReport run(int limit, String cursor) {
                seenLimit.set(limit);
                return new ReDeriveReport(0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0, null, false);
            }

            @Override
            public ReDeriveReport apply(int limit, String cursor) {
                throw new AssertionError("a request without apply must not reach the repair");
            }
        });

        standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/maintenance/re-derive"))
                .andExpect(status().isOk());

        assertEquals(ReDeriveReport.DEFAULT_LIMIT, seenLimit.get());
    }

    @Test
    void answersAStorageOutageAsProblemDetail() throws Exception {
        var controller = new ReDeriveController(new ReDerive() {
            @Override
            public ReDeriveReport run(int limit, String cursor) {
                throw new StorageUnavailableException("model storage is disabled");
            }
        });

        standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/maintenance/re-derive"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Storage unavailable"));
    }
}

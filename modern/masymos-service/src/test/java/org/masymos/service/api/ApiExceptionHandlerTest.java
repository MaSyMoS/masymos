package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-017: the advice owns the error shape, and its two {@code IOException} causes are told apart.
 *
 * <p>The write/read split is driven through the dispatcher rather than by calling the handlers, because
 * the risk is that the {@code IOException} branch wins over {@link WriteFailedException} when both are
 * applicable - a direct call would prove nothing about that.
 */
class ApiExceptionHandlerTest {
    @Test
    void filesystemWriteFailureAnswers500AndNot502() throws Exception {
        standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/probe/write"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://masymos.org/problems/write-failed"))
                .andExpect(jsonPath("$.detail").value("the target directory is not writable"));
    }

    @Test
    void aSourceReadKeepsTheModelSourceUnavailableType() throws Exception {
        standaloneSetup(new FailingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/probe/read"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://masymos.org/problems/model-source-unavailable"));
    }

    /**
     * A04-03: a driver message can name a bolt URI with credentials, and these endpoints have no
     * credential check, so the detail is constant and the real message only reaches the log.
     */
    @Test
    void storageUnavailableDoesNotEchoTheStorageMessage() {
        var problem = new ApiExceptionHandler().handleStorageUnavailable(
                new StorageUnavailableException("bolt://neo4j:secret@db.internal:7687 refused the connection"));

        assertEquals(503, problem.getStatus());
        assertEquals("the graph store is not available", problem.getDetail());
        assertFalse(problem.getDetail().contains("bolt://"), "a driver message must not be published");
    }

    /** A probe whose only job is to raise one exception type per endpoint. */
    @RestController
    static class FailingController {
        @PostMapping("/probe/write")
        void write() throws IOException {
            throw new WriteFailedException("the target directory is not writable");
        }

        @PostMapping("/probe/read")
        void read() throws IOException {
            throw new IOException("cannot read file:///models/a.sbml");
        }
    }
}

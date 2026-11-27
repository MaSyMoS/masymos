package org.masymos.service.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.masymos.core.extract.CellmlExternalCmetaResolver;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.service.config.IndexBoundsProperties;
import org.springframework.http.MediaType;

/**
 * T-017 / GAP02-004: an unknown {@code externalCmeta} is the documented {@code 400 invalid-argument}
 * problem detail, not a {@code ResponseStatusException} whose media shape the advice never promised.
 *
 * <p>RED before the change: the status was {@code 400} but the body and content type were the
 * container's error handling, because no handler produced a problem detail for
 * {@code ResponseStatusException}; the assertion on {@code $.type} had nothing to read.
 */
class CellmlIndexControllerTest {
    @Test
    void unknownExternalCmetaAnswersTheDocumentedInvalidArgumentProblem() throws Exception {
        var service = mock(CellmlModelIndexingService.class);
        var controller = new CellmlIndexController(service, CellmlExternalCmetaResolver.NONE, IndexBoundsProperties.defaults());

        var result = standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/indexes/cellml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sources": ["file:///models/a.cellml"], "externalCmeta": "pmr-ish"}
                                """));

        result.andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://masymos.org/problems/invalid-argument"))
                .andExpect(jsonPath("$.detail").value("externalCmeta must be one of: none, pmr"));

        verifyNoInteractions(service);
    }
}

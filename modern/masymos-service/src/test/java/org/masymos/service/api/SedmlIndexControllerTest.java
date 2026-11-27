package org.masymos.service.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.IndexedModel;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.SedmlModelIndexingService;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.model.SedmlLinkingResult;
import org.masymos.core.spi.SedmlLinkingRepository;
import org.masymos.service.config.IndexBoundsProperties;
import org.masymos.service.config.SedmlLinkProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * T-017 / GAP02-005: a storage outage during the automatic SED-ML link step is the documented {@code
 * 503 storage-unavailable}, not a {@code 201} that claims a complete run with {@code
 * linkStatus=SKIPPED}. {@code SKIPPED} keeps its one honest meaning: automatic linking is off.
 */
class SedmlIndexControllerTest {
    private static final IndexedModel INDEXED = new IndexedModel(
            new ModelSource(new ModelReference(
                    "sim.sedml", URI.create("file:///models/sim.sedml"), ModelType.SEDML, "v1", null)),
            new ModelIdentifier("uid-1", "sim.sedml", "v1", 1L));

    @Test
    void storageOutageDuringLinkingIsReportedAsStorageUnavailable() throws Exception {
        var service = mock(SedmlModelIndexingService.class);
        when(service.indexUris(any())).thenReturn(List.of(INDEXED));
        var linking = mock(SedmlLinkingRepository.class);
        when(linking.linkSedmlReferences()).thenThrow(new StorageUnavailableException("model storage is disabled"));

        perform(new SedmlIndexController(service, linking, new SedmlLinkProperties(true), IndexBoundsProperties.defaults()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://masymos.org/problems/storage-unavailable"));

        verify(linking).linkSedmlReferences();
    }

    @Test
    void anAutoLinkDisabledRunReportsSkipped() throws Exception {
        var service = mock(SedmlModelIndexingService.class);
        when(service.indexUris(any())).thenReturn(List.of(INDEXED));
        var linking = mock(SedmlLinkingRepository.class);

        perform(new SedmlIndexController(service, linking, new SedmlLinkProperties(false), IndexBoundsProperties.defaults()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].linkStatus").value("SKIPPED"));

        verifyNoInteractions(linking);
    }

    @Test
    void aCompletedLinkRunReportsItsCounters() throws Exception {
        var service = mock(SedmlModelIndexingService.class);
        when(service.indexUris(any())).thenReturn(List.of(INDEXED));
        var linking = mock(SedmlLinkingRepository.class);
        when(linking.linkSedmlReferences()).thenReturn(new SedmlLinkingResult(2, 1, 0, 4));

        perform(new SedmlIndexController(service, linking, new SedmlLinkProperties(true), IndexBoundsProperties.defaults()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].linkStatus").value("COMPLETED"))
                .andExpect(jsonPath("$[0].links.modelReferenceLinks").value(2));
    }

    private static ResultActions perform(SedmlIndexController controller) throws Exception {
        return standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/indexes/sedml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sources": ["file:///models/sim.sedml"]}
                                """));
    }
}

package org.masymos.service.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.IndexSummary;
import org.masymos.core.spi.IndexCatalog;
import org.springframework.test.web.servlet.MockMvc;

class IndexCatalogControllerTest {
    @Test
    void listsWhatTheStoreReports() throws Exception {
        var summary = new IndexSummary(
                "modelFulltextV5",
                "FULLTEXT",
                "NODE",
                List.of("Model"),
                List.of("fileId", "name"),
                "ONLINE");

        mockMvc((IndexCatalog) () -> List.of(summary))
                .perform(get("/api/v1/indexes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("modelFulltextV5"))
                .andExpect(jsonPath("$[0].type").value("FULLTEXT"))
                .andExpect(jsonPath("$[0].labelsOrTypes[0]").value("Model"))
                .andExpect(jsonPath("$[0].properties[1]").value("name"))
                .andExpect(jsonPath("$[0].state").value("ONLINE"));
    }

    @Test
    void storageThatCannotAnswerBecomes503() throws Exception {
        var catalog = (IndexCatalog) () -> {
            throw new StorageUnavailableException("model storage is disabled");
        };

        standaloneSetup(new IndexCatalogController(catalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(get("/api/v1/indexes"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://masymos.org/problems/storage-unavailable"));
    }

    private static MockMvc mockMvc(IndexCatalog catalog) {
        return standaloneSetup(new IndexCatalogController(catalog)).build();
    }
}

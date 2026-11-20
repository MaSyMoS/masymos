package org.masymos.service.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.masymos.service.MaSyMoSApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(
        classes = MaSyMoSApplication.class,
        properties = "masymos.neo4j.enabled=false"
)
class OpenApiDocumentationTest {
    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void createMockMvc() {
        mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void exposesOpenApiDescription() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/v1/indexes/sbml']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/indexes/cellml']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/indexes/sedml']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/indexes/owl']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/indexes']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/stats']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/maintenance/sedml/links']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/maintenance/annotations/index']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/models']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/models/{fileId}/latest']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/models/{fileId}/versions']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/annotations']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/persons']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/publications']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/aggregated']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/strategies']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/sedml']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/queries/archives']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/query-fields/{type}']").exists());
    }
}

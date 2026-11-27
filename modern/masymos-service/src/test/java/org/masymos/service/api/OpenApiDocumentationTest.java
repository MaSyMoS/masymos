package org.masymos.service.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

    /**
     * GAP02-008 / C04-4: {@code IndexModelsRequest} is the body of the SBML, CellML, SED-ML and OWL
     * endpoints, so its schema text must not name one language. "The SBML files" was an instruction
     * for the wrong endpoint on three of the four, and a client generated from the document reads the
     * description literally.
     */
    @Test
    void theSharedIndexRequestSchemaNamesNoSingleLanguage() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.IndexModelsRequest.description")
                        .exists())
                .andExpect(jsonPath("$.components.schemas.IndexModelsRequest.description")
                        .value(not(containsString("SBML"))))
                .andExpect(jsonPath("$.components.schemas.IndexModelsRequest.properties.sources.description")
                        .value(not(containsString("SBML"))));
    }

    /**
     * Design decision 48 / finding {@code C04-3}: the default configuration carries no credential
     * <em>by decision</em> - the service binds to loopback and a local process needs none - so the
     * document must declare no {@code security} requirement and no scheme here. When {@code
     * masymos.security.shared-secret} is set the filter enforces the header and the document declares
     * it; {@code SharedSecretSecurityTest} proves that half, so the two tests keep the document and the
     * behaviour telling the same story.
     */
    @Test
    void declaresNoSecurityRequirementWhenNoSharedSecretIsConfigured() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.security").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes").doesNotExist());
    }
}

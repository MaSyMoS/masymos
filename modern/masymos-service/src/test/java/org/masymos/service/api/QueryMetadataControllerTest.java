package org.masymos.service.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class QueryMetadataControllerTest {
    @Test
    void exposesQueryFields() throws Exception {
        MockMvc mockMvc = standaloneSetup(new QueryMetadataController()).build();

        mockMvc.perform(get("/api/v1/query-fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("ID"));
    }

    @Test
    void exposesModelFieldsForTheModelEntity() throws Exception {
        MockMvc mockMvc = standaloneSetup(new QueryMetadataController()).build();

        mockMvc.perform(get("/api/v1/query-fields/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("NAME")))
                .andExpect(jsonPath("$", not(hasItem("OUTPUT_TYPE"))));
    }

    @Test
    void theConstituentPropertiesAreModelFields() throws Exception {
        MockMvc mockMvc = standaloneSetup(new QueryMetadataController()).build();

        mockMvc.perform(get("/api/v1/query-fields/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("COMPONENT")))
                .andExpect(jsonPath("$", hasItem("VARIABLE")))
                .andExpect(jsonPath("$", hasItem("COMPARTMENT")))
                .andExpect(jsonPath("$", hasItem("SPECIES")))
                .andExpect(jsonPath("$", hasItem("REACTION")))
                .andExpect(jsonPath("$", hasItem("PARAMETER")));
    }

    @Test
    void noConstituentEntityOrFieldIsAdvertised() throws Exception {
        MockMvc mockMvc = standaloneSetup(new QueryMetadataController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/query-fields"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", everyItem(not(containsString("CONSTITUENT")))));
        mockMvc.perform(get("/api/v1/query-fields/constituent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("unknown query entity 'constituent'")));
    }

    @Test
    void exposesSedmlFieldsForTheSedmlEntity() throws Exception {
        MockMvc mockMvc = standaloneSetup(new QueryMetadataController()).build();

        mockMvc.perform(get("/api/v1/query-fields/sedml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("OUTPUT_TYPE")))
                .andExpect(jsonPath("$", not(hasItem("COMPONENT"))));
    }

    @Test
    void rejectsAnUnknownEntityType() throws Exception {
        MockMvc mockMvc = standaloneSetup(new QueryMetadataController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mockMvc.perform(get("/api/v1/query-fields/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("model")))
                .andExpect(jsonPath("$.detail").value(containsString("sedml")))
                .andExpect(jsonPath("$.detail").value(containsString("annotation")))
                .andExpect(jsonPath("$.detail").value(containsString("person")))
                .andExpect(jsonPath("$.detail").value(containsString("publication")))
                .andExpect(jsonPath("$.detail").value(containsString("archive")));
    }
}

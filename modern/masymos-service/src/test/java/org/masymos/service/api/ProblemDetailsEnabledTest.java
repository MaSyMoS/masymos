package org.masymos.service.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.masymos.service.MaSyMoSApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * Decision D4 / C04-5: {@code spring.mvc.problemdetails.enabled=true} in {@code application.yml} makes
 * the framework exceptions the advice does not map answer the documented shape.
 *
 * <p>Read from {@code spring-boot-webmvc-4.0.9.jar}'s own metadata before it was set: the key is
 * {@code spring.mvc.problemdetails.enabled}, type {@code Boolean}, default {@code false},
 * "Whether RFC 9457 Problem Details support should be enabled". This test is the behavioural half: if
 * the key were wrong the property would be inert and a {@code 405} would answer no problem body at all.
 * It needs the full context rather than {@code standaloneSetup}, because the property is read by the
 * MVC autoconfiguration.
 */
@SpringBootTest(
        classes = MaSyMoSApplication.class,
        properties = "masymos.neo4j.enabled=false"
)
class ProblemDetailsEnabledTest {
    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void createMockMvc() {
        mockMvc = webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void anUnmappedFrameworkExceptionIsAProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/health"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(405));
    }
}

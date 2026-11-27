package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.masymos.service.MaSyMoSApplication;
import org.masymos.service.security.SharedSecretFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The shared secret enforced end to end over a real port (design decision 48, finding {@code C04-3}).
 *
 * <p>The context is a running servlet container, not MockMvc, because the claim being proved is that
 * Boot puts the filter on the request path of the shipped application - a MockMvc setup would prove that
 * the filter refuses when someone wires it, which is the weaker half. The request goes through a plain
 * {@link HttpClient} on purpose: no test-framework client sits between the assertion and the socket.
 * The document request carries the header too: {@code /v3/api-docs} is behind the same filter here, so
 * the document of an enforced instance is not public either.
 *
 * <p>The document's <em>negative</em> half - no {@code security} requirement and no scheme when no
 * secret is configured - is pinned structurally by {@code
 * OpenApiDocumentationTest#declaresNoSecurityRequirementWhenNoSharedSecretIsConfigured}; this class
 * checks the positive half by text, because the whole JSON shape of an unrelated section is not what this
 * finding is about.
 */
@SpringBootTest(
        classes = MaSyMoSApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "masymos.neo4j.enabled=false",
                "masymos.security.shared-secret=0123456789abcdef0123456789abcdef"
        }
)
class SharedSecretSecurityTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Test
    void aRequestWithoutTheHeaderIsAnUnauthenticatedProblemDetail() throws Exception {
        var response = get("/api/v1/health", null);

        assertEquals(401, response.statusCode());
        var contentType = response.headers().firstValue("content-type").orElse("");
        assertTrue(
                contentType.startsWith("application/problem+json"),
                "the refusal is the documented problem detail, not an empty 401: " + contentType);
        var body = response.body();
        assertTrue(
                body.contains("\"type\":\"https://masymos.org/problems/unauthenticated\""),
                "the documented 401 type: " + body);
        assertTrue(body.contains("\"status\":401"), "the status is in the body too: " + body);
        assertTrue(
                body.contains(SharedSecretFilter.HEADER),
                "the refusal names the header a caller must send: " + body);
        assertTrue(!body.contains(SECRET), "the refusal must never echo the configured secret: " + body);
    }

    @Test
    void aWrongSecretIsRefusedTheSameWay() throws Exception {
        var response = get("/api/v1/health", "fedcba9876543210fedcba9876543210");

        assertEquals(401, response.statusCode());
        assertTrue(
                response.body().contains("\"type\":\"https://masymos.org/problems/unauthenticated\""),
                "a wrong secret is the same refusal: " + response.body());
    }

    @Test
    void theRightSecretReachesTheHandler() throws Exception {
        var response = get("/api/v1/health", SECRET);

        assertEquals(200, response.statusCode());
        assertTrue(
                response.body().contains("\"storage\":\"disabled\""),
                "the handler answered, not the filter: " + response.body());
    }

    @Test
    void theOpenApiDocumentDeclaresTheHeaderTheFilterEnforces() throws Exception {
        var response = get("/v3/api-docs", SECRET);

        assertEquals(200, response.statusCode());
        var body = response.body();
        assertTrue(body.contains("\"sharedSecret\""), "the scheme is declared by name: " + body);
        assertTrue(
                body.contains("\"name\":\"" + SharedSecretFilter.HEADER + "\""),
                "the scheme names the header the filter reads: " + body);
        assertTrue(body.contains("\"in\":\"header\""), "it travels in a header: " + body);
        assertTrue(body.contains("\"apiKey\""), "it is an apiKey scheme: " + body);
    }

    private HttpResponse<String> get(String path, String secret) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (secret != null) {
            request.header(SharedSecretFilter.HEADER, secret);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

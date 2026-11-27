package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HttpAnnotationEgressPolicyTest {

    private static HttpAnnotationEgressPolicy policyAllowing(String host) {
        return new HttpAnnotationEgressPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 1024, Set.of(host), 1);
    }

    @Test
    void bothRealTransportsConsumeTheSamePolicy() {
        var policy = policyAllowing("allowed.example");

        var page = new HttpAnnotationResourceFetcher(policy).fetch("https://evil.example/page");
        assertFalse(page.succeeded());
        assertEquals("host not allowed: evil.example", page.failureReason());

        var provider = AnnotationProviderHttp.jdk(policy);
        var thrown = assertThrows(IllegalStateException.class,
                () -> provider.send(AnnotationProviderHttp.ProviderRequest.get("https://evil.example/x", Map.of())));
        assertTrue(thrown.getMessage().contains("host not allowed: evil.example"), thrown.getMessage());
    }
}

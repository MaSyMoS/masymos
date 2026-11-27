package org.masymos.service.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.masymos.service.security.SharedSecretFilter;
import org.masymos.service.security.SharedSecretProperties;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the shared-secret header in the OpenAPI document exactly when the filter enforces it
 * (design decision 48, finding {@code C04-3}).
 *
 * <p>The document declares the header only when the filter enforces it, because a {@code security}
 * requirement in the document of an unprotected instance is a lie in the other direction: a generated
 * client would send a credential nothing checks and read as if the surface were guarded. So when the
 * secret is not configured the customizer returns without touching the document, and the default
 * document stays what it was.
 */
@Configuration
@EnableConfigurationProperties(SharedSecretProperties.class)
class SharedSecretConfiguration {

    @Bean
    GlobalOpenApiCustomizer sharedSecretIsDocumentedOnlyWhenItIsEnforced(SharedSecretProperties properties) {
        return openApi -> {
            if (!properties.enabled()) {
                return;
            }
            declare(openApi);
        };
    }

    private static void declare(OpenAPI openApi) {
        var components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
        components.addSecuritySchemes(
                "sharedSecret",
                new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(SharedSecretFilter.HEADER)
                        .description("The service's shared secret, sent verbatim in the "
                                + SharedSecretFilter.HEADER
                                + " header. Required only when masymos.security.shared-secret is set"));
        openApi.setComponents(components);
        openApi.addSecurityItem(new SecurityRequirement().addList("sharedSecret"));
    }
}

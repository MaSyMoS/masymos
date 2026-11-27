package org.masymos.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "masymos.sedml.links")
public record SedmlLinkProperties(boolean autoLink) {
    public static SedmlLinkProperties defaults() {
        return new SedmlLinkProperties(true);
    }
}

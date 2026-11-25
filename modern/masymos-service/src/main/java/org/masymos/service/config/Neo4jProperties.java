package org.masymos.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "masymos.neo4j")
public record Neo4jProperties(
        String uri,
        String username,
        String password,
        String database
) {
}

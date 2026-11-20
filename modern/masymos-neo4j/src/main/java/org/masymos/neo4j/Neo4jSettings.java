package org.masymos.neo4j;

import java.net.URI;
import java.util.Objects;

public record Neo4jSettings(URI uri, String username, String password, String database) {
    public Neo4jSettings {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        database = database == null || database.isBlank() ? "neo4j" : database;
    }
}

package org.masymos.neo4j;

import java.util.List;
import java.util.Map;
import org.masymos.core.model.IndexSummary;
import org.masymos.core.spi.IndexCatalog;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

/**
 * Reports the store's own index catalogue with {@code SHOW INDEXES}.
 *
 * <p>Nothing is derived: the rows are what the database says about its indexes, including a state such
 * as {@code POPULATING} or {@code FAILED}. That is the point of the listing — a create statement in the
 * initializer is a claim, the catalogue is the answer.</p>
 */
public final class Neo4jIndexCatalog implements IndexCatalog {
    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jIndexCatalog(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.forDatabase(database);
    }

    @Override
    public List<IndexSummary> list() {
        try (var session = driver.session(sessionConfig)) {
            return session.run(listCypher()).list(record -> toSummary(record.asMap()));
        }
    }

    static String listCypher() {
        return """
                SHOW INDEXES
                YIELD name, type, entityType, labelsOrTypes, properties, state
                RETURN name, type, entityType, labelsOrTypes, properties, state
                ORDER BY name
                """;
    }

    static IndexSummary toSummary(Map<String, Object> row) {
        return new IndexSummary(
                text(row.get("name")),
                text(row.get("type")),
                text(row.get("entityType")),
                texts(row.get("labelsOrTypes")),
                texts(row.get("properties")),
                text(row.get("state")));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> texts(Object value) {
        if (value instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}

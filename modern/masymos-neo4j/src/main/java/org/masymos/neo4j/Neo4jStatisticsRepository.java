package org.masymos.neo4j;

import java.util.Map;
import org.masymos.core.model.StoreStatistics;
import org.masymos.core.spi.StatisticsRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

/**
 * Counts the stored graph in one read.
 *
 * <p>Each count is a subquery, so a missing label reports {@code 0} instead of dropping the row, and
 * the whole statistic costs one round trip. The labels are the ones the query surface filters on, so
 * {@code sbmlModels} and a query with {@code modelTypes: ["SBML"]} count the same nodes.</p>
 */
public final class Neo4jStatisticsRepository implements StatisticsRepository {
    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jStatisticsRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.forDatabase(database);
    }

    @Override
    public StoreStatistics statistics() {
        try (var session = driver.session(sessionConfig)) {
            var row = session.run(statisticsCypher()).single();
            return toStatistics(row.asMap());
        }
    }

    static String statisticsCypher() {
        return """
                CALL { MATCH (n:Document) RETURN count(n) AS documents }
                CALL { MATCH (n:Model) RETURN count(n) AS models }
                CALL { MATCH (n:SBML_MODEL) RETURN count(n) AS sbmlModels }
                CALL { MATCH (n:CELLML_MODEL) RETURN count(n) AS cellmlModels }
                CALL { MATCH (n:SEDML_MODEL) RETURN count(n) AS sedmlModels }
                CALL { MATCH (n:OWL_MODEL) RETURN count(n) AS owlModels }
                CALL { MATCH (n:PERSON) RETURN count(n) AS persons }
                CALL { MATCH (n:PUBLICATION) RETURN count(n) AS publications }
                CALL { MATCH (n:RESOURCE) RETURN count(n) AS annotations }
                CALL { MATCH (n:CombineArchive) RETURN count(n) AS archives }
                RETURN documents, models, sbmlModels, cellmlModels, sedmlModels, owlModels,
                       persons, publications, annotations, archives
                """;
    }

    static StoreStatistics toStatistics(Map<String, Object> row) {
        return new StoreStatistics(
                number(row.get("documents")),
                number(row.get("models")),
                number(row.get("sbmlModels")),
                number(row.get("cellmlModels")),
                number(row.get("sedmlModels")),
                number(row.get("owlModels")),
                number(row.get("persons")),
                number(row.get("publications")),
                number(row.get("annotations")),
                number(row.get("archives")));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

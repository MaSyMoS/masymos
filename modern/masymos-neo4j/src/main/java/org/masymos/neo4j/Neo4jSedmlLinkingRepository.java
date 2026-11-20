package org.masymos.neo4j;

import org.masymos.core.model.SedmlLinkingResult;
import org.masymos.core.spi.SedmlLinkingRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

public final class Neo4jSedmlLinkingRepository implements SedmlLinkingRepository {
    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jSedmlLinkingRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public SedmlLinkingResult linkSedmlReferences() {
        try (var session = driver.session(sessionConfig)) {
            return session.executeWrite(tx -> new SedmlLinkingResult(
                    runCount(tx, SedmlLinkingCypher.linkModelReferences()),
                    runCount(tx, SedmlLinkingCypher.linkSbmlObservedVariables()),
                    runCount(tx, SedmlLinkingCypher.linkCellmlObservedVariables())
            ));
        }
    }

    private static long runCount(org.neo4j.driver.TransactionContext tx, PreparedGraphWrite write) {
        return tx.run(write.cypher(), write.parameters()).single().get("relationshipCount").asLong();
    }
}

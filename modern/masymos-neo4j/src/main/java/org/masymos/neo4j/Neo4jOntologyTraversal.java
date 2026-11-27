package org.masymos.neo4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.masymos.core.spi.OntologySimilarity;
import org.masymos.core.spi.OntologyTerm;
import org.masymos.core.spi.OntologyTraversal;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;

/**
 * The ontology traversal over the graph an {@code OwlOntologyIndexingService} wrote.
 *
 * <p>Every query comes from {@link OntologyQueryCypher}, which is package-private for that reason;
 * this class only runs them and shapes the result. All four operations are reads over the asserted
 * {@code isA} edges — no reasoner is involved (see {@code OWL-1}), so a relation that only an
 * inference would produce does not count towards a depth, an ancestor or a score.
 *
 * <p>{@link #similarity} needs three reads: the shortest distance between the terms, their lowest
 * common ancestor (which the Cypher orders by path length, so it is the lowest one), and that
 * ancestor's own depth to the root. It stops as soon as one leg comes back empty, because a partial
 * answer would be a score computed over a number that was not found.
 */
public final class Neo4jOntologyTraversal implements OntologyTraversal {
    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jOntologyTraversal(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public Optional<OntologyTerm> term(String ontology, String id) {
        return termFrom(OntologyQueryCypher.nodeById(ontology, id));
    }

    @Override
    public Optional<OntologyTerm> root(String ontology) {
        return termFrom(OntologyQueryCypher.root(ontology));
    }

    @Override
    public Optional<Integer> depth(String ontology, String id, int maxDepth) {
        return read(OntologyQueryCypher.depth(ontology, id, maxDepth), record -> record.get("depth").asInt());
    }

    @Override
    public Optional<OntologySimilarity> similarity(String ontology, String first, String second, int maxDepth) {
        if (first != null && first.equals(second)) {
            // A term is its own lowest common ancestor at distance zero; the pattern below cannot say
            // that, because an isA path needs at least one relationship.
            return depth(ontology, first, maxDepth).map(depth -> new OntologySimilarity(
                    0, first, depth, OntologyQueryCypher.similarity(0, depth)));
        }
        var pathLength = read(
                OntologyQueryCypher.shortestPathLength(ontology, first, second, maxDepth),
                record -> record.get("pathLength").asInt());
        if (pathLength.isEmpty()) {
            return Optional.empty();
        }
        var ancestorId = read(
                OntologyQueryCypher.lowestCommonAncestor(ontology, first, second, maxDepth),
                record -> record.get("id").asString(null));
        if (ancestorId.isEmpty()) {
            return Optional.empty();
        }
        var ancestorDepth = depth(ontology, ancestorId.get(), maxDepth);
        if (ancestorDepth.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OntologySimilarity(
                pathLength.get(),
                ancestorId.get(),
                ancestorDepth.get(),
                OntologyQueryCypher.similarity(pathLength.get(), ancestorDepth.get())));
    }

    private Optional<OntologyTerm> termFrom(PreparedGraphWrite write) {
        return read(write, record -> {
            var node = record.get("term");
            return node.isNull() ? null : new OntologyTerm(node.get("id").asString(null), propertiesOf(node));
        });
    }

    /** The node's string properties; a non-string value has no place in the term's property map. */
    private static Map<String, String> propertiesOf(Value node) {
        var properties = new LinkedHashMap<String, String>();
        node.asMap().forEach((name, value) -> {
            if (value instanceof String text) {
                properties.put(name, text);
            }
        });
        return properties;
    }

    private <T> Optional<T> read(PreparedGraphWrite write, Function<Record, T> mapper) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                var records = tx.run(write.cypher(), write.parameters()).list();
                if (records.isEmpty()) {
                    return Optional.<T>empty();
                }
                return Optional.ofNullable(mapper.apply(records.get(0)));
            });
        }
    }
}

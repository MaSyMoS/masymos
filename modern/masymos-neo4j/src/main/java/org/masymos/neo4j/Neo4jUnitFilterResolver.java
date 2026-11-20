package org.masymos.neo4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.masymos.core.units.UnitCatalogue;
import org.masymos.core.units.UnitNameResolution;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

/**
 * Turns the unit names a caller wrote into the canonical keys the graph is keyed by (CML-1).
 *
 * <p>One key set per requested entry: the entries combine with AND, so the caller must keep them
 * apart (design 4.4). Inside one entry the keys are alternatives.
 *
 * <p>Two steps, in this order: the specification's own names, aliases and prefix forms resolve
 * without touching the database; anything else is looked up in the {@code names} property of the
 * unit nodes, which is how a unit a model declared for itself is still filterable. A name that
 * resolves to nothing is an error, never an empty result set - "no model uses furlongs" and "there
 * is no such unit" are different answers.
 */
final class Neo4jUnitFilterResolver {

    private static final String NAMES_QUERY =
            "MATCH (u:UNIT) WHERE $name IN split(u.names, ' ') RETURN DISTINCT u.key AS key";

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final UnitNameResolution resolution;

    Neo4jUnitFilterResolver(Driver driver, SessionConfig sessionConfig, UnitCatalogue catalogue) {
        this.driver = driver;
        this.sessionConfig = sessionConfig;
        this.resolution = new UnitNameResolution(catalogue);
    }

    /** One key set per entry, in request order; throws for a name nothing defines. */
    List<List<String>> resolve(Collection<String> units) {
        var groups = new ArrayList<List<String>>();
        if (units == null) {
            return List.of();
        }
        for (var unit : units) {
            if (unit == null || unit.isBlank()) {
                continue;
            }
            var name = unit.trim();
            var resolved = new LinkedHashSet<>(resolution.keysFor(name));
            if (resolved.isEmpty()) {
                resolved.addAll(graphKeys(name));
            }
            if (resolved.isEmpty()) {
                throw new IllegalArgumentException("unknown unit: " + name);
            }
            groups.add(List.copyOf(resolved));
        }
        return List.copyOf(groups);
    }

    private List<String> graphKeys(String name) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> tx.run(NAMES_QUERY, Map.of("name", name))
                    .list(record -> record.get("key").asString()));
        }
    }
}

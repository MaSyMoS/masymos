package org.masymos.core.units;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.masymos.core.extract.ParsedEntityRelationship;
import org.masymos.core.extract.ParsedModelEntity;
import org.masymos.core.extract.ParsedModelEntity.EntityType;

/**
 * Turns canonical units into the graph entities and edges the writer consumes: one {@code UNIT}
 * entity per key, and one {@code COMPOSED_OF} edge per named component.
 *
 * <p>Units are the only shared entities, so this is also the only place that de-duplicates: two
 * models defining the same unit agree on its key by construction, and one document that names the
 * same unit twice emits one node and one edge.
 */
public final class UnitEntityBuilder {

    private UnitEntityBuilder() {}

    /** One {@code UNIT} entity per distinct key, in first-seen order. */
    public static List<ParsedModelEntity> entities(Collection<CanonicalUnit> units) {
        var byKey = new LinkedHashMap<String, CanonicalUnit>();
        units.forEach(unit -> byKey.putIfAbsent(unit.key(), unit));
        return byKey.values().stream().map(UnitEntityBuilder::entity).toList();
    }

    public static ParsedModelEntity entity(CanonicalUnit unit) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("key", unit.key());
        properties.put("display", unit.display());
        properties.put("names", String.join(" ", unit.names()));
        properties.put("kind", unit.kind());
        properties.put("dimension", unit.dimension());
        properties.put("multiplier", unit.multiplier());
        if (unit.offset() != null) {
            properties.put("offset", unit.offset());
        }
        return new ParsedModelEntity(EntityType.UNIT, unit.key(), unit.display(), Map.copyOf(properties));
    }

    /** The definition graph: a unit's named components, never its flattened bases. */
    public static List<ParsedEntityRelationship> composition(Collection<CanonicalUnit> units) {
        var edges = new ArrayList<ParsedEntityRelationship>();
        var seen = new HashMap<String, Boolean>();
        for (var unit : units) {
            for (var component : unit.components()) {
                var identity = unit.key() + "->" + component.key() + "^" + component.exponent() + "*"
                        + component.multiplier();
                if (seen.putIfAbsent(identity, Boolean.TRUE) != null) {
                    continue;
                }
                edges.add(new ParsedEntityRelationship(
                        "UNIT:" + unit.key(),
                        "COMPOSED_OF",
                        "UNIT:" + component.key(),
                        Map.of("exponent", component.exponent(), "multiplier", component.multiplier())));
            }
        }
        return List.copyOf(edges);
    }
}

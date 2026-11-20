package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.ParsedModelEntity;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.units.CanonicalUnit;
import org.masymos.core.units.UnitComponent;
import org.masymos.core.units.UnitEntityBuilder;

/**
 * The write path of shared units (CML-1, design 4.2): a unit is merged on its key and never stamped
 * with {@code ownerUid}, its definition edges are merged on their own identity, and the model points
 * at it with a per-document {@code USES_UNIT}.
 */
class UnitGraphCypherTest {

    @Test
    void unitNodesAreMergedNotCreated() {
        var write = ParsedModelGraphCypher.prepare(graph(baseMetre()));

        assertTrue(allCypher(write).contains("MERGE (n:UNIT {key: row.unitKey})"), allCypher(write));
        var usesUnit = relationshipBatch(write, "USES_UNIT");
        assertEquals(1, usesUnit.rows().size(), allCypher(write));
        assertEquals("model", usesUnit.rows().getFirst().startKey());
        assertEquals("unit:base:metre", usesUnit.rows().getFirst().endKey());
        assertEquals("base:metre", unitKey(write), "the row carries the canonical key the block merges on");
    }

    @Test
    void twoDefinitionsOfOneNameMergeOnTwoKeys() {
        var a = new CanonicalUnit("unit:1*metre^1*second^-1", "per_second", List.of("per_second"), "derived", "metre^1*second^-1", "1", null, List.of());
        var b = new CanonicalUnit("unit:2*metre^1*second^-1", "per_second", List.of("per_second"), "derived", "metre^1*second^-1", "2", null, List.of());

        var first = unitKey(ParsedModelGraphCypher.prepare(graph(a)));
        var second = unitKey(ParsedModelGraphCypher.prepare(graph(b)));

        assertEquals("unit:1*metre^1*second^-1", first);
        assertEquals("unit:2*metre^1*second^-1", second);
        assertFalse(first.equals(second), "one name, two definitions, two nodes");
    }

    @Test
    void unitNodesAreNotOwnedByTheDocument() {
        var write = ParsedModelGraphCypher.prepare(graph(baseMetre()));
        var properties = unitProperties(write);

        assertFalse(properties.containsKey("ownerUid"), properties.toString());
        assertTrue(properties.containsKey("key"), properties.toString());
    }

    @Test
    void compositionEdgesAreMergedOnTheirOwnIdentity() {
        var metre = baseMetre();
        var doubled = new CanonicalUnit(
                "unit:2*metre^1",
                "double_metre",
                List.of("double_metre"),
                "derived",
                "metre^1",
                "2",
                null,
                List.of(new UnitComponent("base:metre", "1", "2")));

        var write = ParsedModelGraphCypher.prepare(graph(doubled, metre));

        var composedOf = relationshipBatch(write, "COMPOSED_OF");
        assertTrue(composedOf.mergesOnIdentityTriple(), "a definition edge is merged, not created");
        assertTrue(
                allCypher(write).contains(":COMPOSED_OF {exponent: row.properties.exponent, multiplier: row.properties.multiplier}"),
                allCypher(write));
        assertTrue(allCypher(write).contains("MERGE (start)-[relationship:COMPOSED_OF"), allCypher(write));
    }

    @Test
    void theSchemaKeepsUnitKeysUnique() {
        var statements = Neo4jSchemaInitializer.statements();

        assertTrue(
                statements.stream().anyMatch(statement -> statement.contains("unit_key_unique")
                        && statement.contains("FOR (u:UNIT)")
                        && statement.contains("u.key IS UNIQUE")),
                statements.toString());
    }

    /** The value the UNIT block merges on, read from the row rather than from a parameter name. */
    private static Object unitKey(BatchedGraphWrite write) {
        return unitRow(write).get("unitKey");
    }

    private static Map<?, ?> unitProperties(BatchedGraphWrite write) {
        return assertInstanceOf(Map.class, unitRow(write).get("properties"));
    }

    private static Map<String, Object> unitRow(BatchedGraphWrite write) {
        return nodeRows(write).stream()
                .filter(row -> row.containsKey("unitKey"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no UNIT row in " + allCypher(write)));
    }

    private static List<Map<String, Object>> nodeRows(BatchedGraphWrite write) {
        var rows = new ArrayList<Map<String, Object>>();
        write.nodes().forEach(statement -> assertInstanceOf(List.class, statement.parameters().get("rows"))
                .forEach(row -> {
                    assertInstanceOf(Map.class, row);
                    @SuppressWarnings("unchecked")
                    var typed = (Map<String, Object>) row;
                    rows.add(typed);
                }));
        return rows;
    }

    private static BatchedGraphWrite.RelationshipBatch relationshipBatch(BatchedGraphWrite write, String type) {
        return write.relationships().stream()
                .filter(batch -> batch.type().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " batch in " + allCypher(write)));
    }

    private static String allCypher(BatchedGraphWrite write) {
        var text = new StringBuilder(write.document().cypher());
        write.nodes().forEach(statement -> text.append('\n').append(statement.cypher()));
        var elementIdsByKey = new java.util.HashMap<String, String>();
        elementIdsByKey.put("document", "document-element-id");
        nodeRows(write).forEach(row -> elementIdsByKey.put(row.get("nodeKey").toString(), "element-id"));
        write.relationshipStatements(elementIdsByKey).forEach(statement -> text.append('\n').append(statement.cypher()));
        return text.toString();
    }

    private static CanonicalUnit baseMetre() {
        return new CanonicalUnit("base:metre", "metre", List.of("metre"), "base", "metre^1", "1", null, List.of());
    }

    private static ExtractedModelGraph graph(CanonicalUnit... units) {
        var entities = new ArrayList<ParsedModelEntity>();
        entities.addAll(UnitEntityBuilder.entities(List.of(units)));
        entities.add(new ParsedModelEntity(EntityType.VARIABLE, "main/c", "c", Map.of("units", units[0].display())));
        var relationships = UnitEntityBuilder.composition(List.of(units));
        return new ParsedModelGraphMapper()
                .toGraph(new ParsedModel(
                        new ModelReference("demo.cellml", URI.create("file:///demo.cellml"), ModelType.CELLML),
                        "demo",
                        "demo",
                        Map.of(),
                        entities,
                        List.of(),
                        List.of(),
                        List.of(),
                        relationships));
    }
}

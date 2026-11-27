package org.masymos.core.units;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.masymos.core.extract.ExtractedGraphNode;
import org.masymos.core.extract.ExtractedModelGraph;

/**
 * The unit layer of one document, taken out of a parsed graph (STO-6): the shared {@code UNIT} nodes,
 * their definition edges, the units the model uses, and the entities that declare one.
 *
 * <p>Why a separate view at all: a backfill must write <em>only</em> this layer into a document that
 * already exists, so it cannot reuse the document write, which creates the {@code Document} node, the
 * model, every entity and its own {@code ownerUid}. This record is the seam between the parsers (which
 * know nothing about the graph) and a write path that touches nothing else.
 *
 * <p>Endpoints are described the way the <em>database</em> identifies them, not the way the parser's
 * graph keys them: a unit by its canonical {@code key} property, an entity by the label it was written
 * with and its {@code id} property, because only those survive into the graph. The parser's node keys
 * ({@code unit:base:metre}, {@code cellml_variable:a/b}) are internal to one write.
 */
public record UnitLayer(
        List<ExtractedGraphNode> units,
        List<Composition> compositions,
        List<String> uses,
        List<EntityUnit> hasUnits) {

    /** The label the graph gives a unit node. */
    public static final String UNIT_LABEL = "UNIT";

    /** One definition edge: {@code start} is composed of {@code end}. */
    public record Composition(String startKey, String endKey, String exponent, String multiplier) {
    }

    /** One entity that declares a unit: its label and {@code id} property, plus the edge properties. */
    public record EntityUnit(String label, String id, String unitKey, Map<String, String> properties) {
        public EntityUnit {
            properties = Map.copyOf(properties);
        }
    }

    public UnitLayer {
        units = List.copyOf(units);
        compositions = List.copyOf(compositions);
        uses = List.copyOf(uses);
        hasUnits = List.copyOf(hasUnits);
    }

    /**
     * Extracts the layer from a whole parsed graph. Entities that declare no unit, annotations,
     * publications and the document itself are dropped: this view exists to write the unit layer of a
     * document that is already in the graph.
     */
    public static UnitLayer of(ExtractedModelGraph graph) {
        var unitNodes = new ArrayList<ExtractedGraphNode>();
        for (var node : graph.nodes()) {
            if (node.labels().contains(UNIT_LABEL) && keyOf(node) != null) {
                unitNodes.add(node);
            }
        }
        var uses = new LinkedHashSet<String>();
        var compositions = new ArrayList<Composition>();
        var hasUnits = new ArrayList<EntityUnit>();
        for (var relationship : graph.relationships()) {
            switch (relationship.type()) {
                case "USES_UNIT" -> {
                    // A model is the only anchor a USES_UNIT may start from in a parsed graph.
                    if (!"model".equals(relationship.startKey())) {
                        continue;
                    }
                    var unitKey = unitKey(graph, relationship.endKey());
                    if (unitKey != null) {
                        uses.add(unitKey);
                    }
                }
                case "COMPOSED_OF" -> {
                    var start = unitKey(graph, relationship.startKey());
                    var end = unitKey(graph, relationship.endKey());
                    if (start != null && end != null) {
                        compositions.add(new Composition(
                                start,
                                end,
                                relationship.properties().getOrDefault("exponent", "1"),
                                relationship.properties().getOrDefault("multiplier", "1")));
                    }
                }
                case "HAS_UNIT" -> {
                    var unitKey = unitKey(graph, relationship.endKey());
                    var entity = entity(graph, relationship.startKey());
                    if (unitKey != null && entity != null) {
                        hasUnits.add(new EntityUnit(
                                entity.label(), entity.id(), unitKey, relationship.properties()));
                    }
                }
                default -> {
                }
            }
        }
        return new UnitLayer(unitNodes, compositions, List.copyOf(uses), hasUnits);
    }

    /** True when the parsed document declares or uses no unit at all. */
    public boolean isEmpty() {
        return units.isEmpty() && uses.isEmpty() && hasUnits.isEmpty();
    }

    /** The unit keys of this layer, in first-seen order, without duplicates. */
    public Set<String> unitKeys() {
        var keys = new LinkedHashSet<String>();
        units.forEach(node -> keys.add(keyOf(node)));
        return keys;
    }

    /** One unit node with the write-path bookkeeping removed, as the graph should store it. */
    public record UnitNode(String key, Map<String, String> properties) {
        public UnitNode {
            properties = Map.copyOf(properties);
        }
    }

    /** The unit nodes as {@code MERGE} input: canonical key plus properties, never {@code ownerUid}. */
    public List<UnitNode> unitNodes() {
        var nodes = new ArrayList<UnitNode>();
        for (var unit : units) {
            var properties = new LinkedHashMap<>(unit.properties());
            properties.remove("ownerUid");
            properties.put("key", keyOf(unit));
            nodes.add(new UnitNode(keyOf(unit), properties));
        }
        return List.copyOf(nodes);
    }

    /** An entity endpoint as the graph identifies it: one non-model label plus the {@code id}. */
    private record Endpoint(String label, String id) {
    }

    private static Endpoint entity(ExtractedModelGraph graph, String nodeKey) {
        for (var node : graph.nodes()) {
            if (!node.key().equals(nodeKey) || node.labels().contains(UNIT_LABEL)) {
                continue;
            }
            var id = node.properties().get("id");
            if (id == null || id.isBlank()) {
                return null;
            }
            for (var label : node.labels()) {
                if (!"Model".equals(label) && !"Document".equals(label)) {
                    return new Endpoint(label, id);
                }
            }
            return null;
        }
        return null;
    }

    private static String keyOf(ExtractedGraphNode node) {
        var key = node.properties().get("key");
        return key == null || key.isBlank() ? null : key;
    }

    /**
     * The canonical key of the {@code UNIT} node a relationship points at, or {@code null} when the
     * node is not a unit of this graph.
     */
    private static String unitKey(ExtractedModelGraph graph, String nodeKey) {
        for (var node : graph.nodes()) {
            if (!node.key().equals(nodeKey)) {
                continue;
            }
            if (!node.labels().contains(UNIT_LABEL)) {
                return null;
            }
            var key = keyOf(node);
            if (key != null) {
                return key;
            }
            return nodeKey.startsWith("unit:") ? nodeKey.substring("unit:".length()) : null;
        }
        return null;
    }
}

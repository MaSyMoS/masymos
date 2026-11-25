package org.masymos.core.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * STO-6: the unit layer a backfill writes is extracted from a parsed graph and keyed the way the graph
 * stores it - units by canonical {@code key}, entities by label plus {@code id} - never by the parser's
 * internal node keys, which do not survive into the database.
 */
class UnitLayerTest {

    private static final String MODEL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sbml xmlns="http://www.sbml.org/sbml/level3/version1/core" level="3" version="1">
              <model id="layer">
                <listOfUnitDefinitions>
                  <unitDefinition id="per_ms">
                    <listOfUnits>
                      <unit kind="second" exponent="-1" scale="-3"/>
                    </listOfUnits>
                  </unitDefinition>
                </listOfUnitDefinitions>
                <listOfCompartments>
                  <compartment id="cell" size="1" units="litre"/>
                </listOfCompartments>
                <listOfSpecies>
                  <species id="s1" compartment="cell" substanceUnits="per_ms" hasOnlySubstanceUnits="true"/>
                </listOfSpecies>
              </model>
            </sbml>
            """;

    @Test
    void extractsUnitsTheirCompositionTheModelUseAndTheDeclaringEntity() {
        var layer = layer();

        assertFalse(layer.isEmpty());
        assertTrue(layer.unitKeys().contains("base:second"), layer.unitKeys().toString());
        assertTrue(layer.uses().contains("base:second"), "the model uses the unit its species is declared in");
        assertTrue(
                layer.compositions().stream()
                        .anyMatch(composition -> "unit:1000*second^-1".equals(composition.startKey())
                                && "base:second".equals(composition.endKey())),
                layer.compositions().toString());

        var species = layer.hasUnits().stream()
                .filter(entity -> "SBML_SPECIES".equals(entity.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no species edge in " + layer.hasUnits()));
        assertEquals("s1", species.id(), "the endpoint is the id the graph stores, not the parser key");
        assertEquals("per_ms", species.properties().get("declaredName"));
        assertEquals("unit:1000*second^-1", species.unitKey());
    }

    @Test
    void unitNodesCarryTheCanonicalKeyAndNoOwner() {
        var layer = layer();

        var nodes = layer.unitNodes();
        assertEquals(layer.unitKeys().size(), nodes.size(), "one MERGE input per distinct unit key");
        assertTrue(nodes.stream().noneMatch(node -> node.properties().containsKey("ownerUid")),
                "a backfill must not invent an owner for a shared node");
        assertTrue(nodes.stream().allMatch(node -> node.key().equals(node.properties().get("key"))),
                "the canonical key is the identity the write merges on");
    }

    @Test
    void aModelThatStatesNoUnitYieldsAnEmptyLayer() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version1/core" level="3" version="1">
                  <model id="nothing"/>
                </sbml>
                """;

        assertTrue(layerOf(xml).isEmpty(), "an empty layer is complete, not a failed backfill");
    }

    private static UnitLayer layer() {
        return layerOf(MODEL);
    }

    private static UnitLayer layerOf(String xml) {
        var reference = new ModelReference(
                "layer.xml", URI.create("https://example.org/layer.xml"), ModelType.SBML);
        try (var stream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            var parsed = new SbmlModelParser().parse(new ModelSource(reference), stream);
            return UnitLayer.of(new ParsedModelGraphMapper().toGraph(parsed));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void everyEntityThatDeclaresAUnitIsPartOfTheLayer() {
        var layer = layer();
        assertEquals(
                java.util.Set.of("SBML_COMPARTMENT:cell", "SBML_SPECIES:s1"),
                layer.hasUnits().stream()
                        .map(entity -> entity.label() + ":" + entity.id())
                        .collect(java.util.stream.Collectors.toSet()),
                layer.hasUnits().toString());
    }
}

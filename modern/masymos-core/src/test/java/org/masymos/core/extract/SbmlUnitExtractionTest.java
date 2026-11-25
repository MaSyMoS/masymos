package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * SBML unit extraction (CML-1): every {@code unitDefinition} becomes a {@code UNIT} entity with its
 * composition, and the declared unit references become {@code HAS_UNIT} edges. Level 1 declares
 * definitions by {@code name}, has no multiplier, and its special {@code substance}/{@code volume}
 * definitions carry the model's scale.
 */
class SbmlUnitExtractionTest {

    @Test
    void readsUnitDefinitionsAndTheirComposition() throws Exception {
        var model = parse("units/sbml-l3-units.xml");

        assertTrue(
                model.entities().stream()
                        .anyMatch(entity -> entity.type() == EntityType.UNIT
                                && "foot".equals(entity.properties().get("display"))),
                model.entities().toString());
        assertTrue(
                model.entityRelationships().stream()
                        .anyMatch(relationship -> relationship.type().equals("COMPOSED_OF")
                                && "0.3048".equals(relationship.properties().get("multiplier"))),
                model.entityRelationships().toString());
        assertTrue(
                model.entityRelationships().stream()
                        .anyMatch(relationship -> relationship.type().equals("HAS_UNIT")
                                && relationship.startEntityKey().equals("SPECIES:S1")
                                && relationship.endEntityKey().equals("UNIT:base:mole")),
                model.entityRelationships().toString());
    }

    @Test
    void l1SubstanceAndVolumeCarryTheModelScale() throws Exception {
        var model = parse("units/sbml-l1v2-volumes.xml");

        assertTrue(
                model.entities().stream()
                        .anyMatch(entity ->
                                entity.type() == EntityType.UNIT && entity.id().equals("unit:0.001*mole^1")),
                model.entities().toString());
        assertTrue(
                model.entities().stream()
                        .anyMatch(entity -> entity.type() == EntityType.UNIT
                                && entity.id().equals("unit:0.000001*metre^3")),
                model.entities().toString());
    }

    @Test
    void l2v1CelsiusKeepsItsOffset() throws Exception {
        var model = parse("units/sbml-l2v1-celsius.xml");

        assertTrue(
                model.entities().stream()
                        .anyMatch(entity ->
                                entity.type() == EntityType.UNIT && "273.15".equals(entity.properties().get("offset"))),
                model.entities().toString());
    }

    private static ParsedModel parse(String fixture) throws Exception {
        var reference = new ModelReference(
                Path.of(fixture).getFileName().toString(),
                URI.create("https://example.org/" + fixture),
                ModelType.SBML);
        try (InputStream stream = SbmlUnitExtractionTest.class.getClassLoader().getResourceAsStream(fixture)) {
            return new SbmlModelParser().parse(new ModelSource(reference), stream);
        }
    }
}

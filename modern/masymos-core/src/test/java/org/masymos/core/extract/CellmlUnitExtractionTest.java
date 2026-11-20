package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * CellML unit extraction (CML-1): every declared {@code units} element becomes a {@code UNIT}
 * entity, every variable's {@code units} attribute a {@code HAS_UNIT} edge, and the 1.x scoping
 * rules decide which declaration a name means inside a component.
 */
class CellmlUnitExtractionTest {

    @Test
    void readsTheFrielUnitsAndLinksVariablesToThem() throws Exception {
        var model = parse("pmr/friel_1995/friel_1995.cellml");

        var units = model.entities().stream()
                .filter(entity -> entity.type() == EntityType.UNIT)
                .toList();
        assertTrue(
                units.stream().anyMatch(unit -> unit.id().equals("unit:0.000001*metre^-3*mole^1")),
                units.toString());
        assertTrue(
                model.entityRelationships().stream()
                        .anyMatch(relationship -> relationship.type().equals("HAS_UNIT")
                                && "nanomolar".equals(relationship.properties().get("declaredName"))),
                model.entityRelationships().toString());
    }

    @Test
    void anEmptyUnitsElementIsADeclaredBase() throws Exception {
        var model = parse("units/cellml20-declared-base.cellml");

        assertTrue(
                model.entities().stream()
                        .anyMatch(entity ->
                                entity.type() == EntityType.UNIT && entity.id().equals("declared-base:cell")),
                model.entities().toString());
        assertTrue(
                model.entityRelationships().stream()
                        .anyMatch(relationship -> relationship.type().equals("HAS_UNIT")
                                && relationship.endEntityKey().equals("UNIT:declared-base:cell")),
                model.entityRelationships().toString());
    }

    @Test
    void oneOneBaseUnitsAndOffsetsAreRecorded() throws Exception {
        var model = parse("units/cellml11-base-units.cellml");

        assertTrue(
                model.entities().stream()
                        .anyMatch(entity ->
                                entity.type() == EntityType.UNIT && entity.id().equals("declared-base:ph")),
                model.entities().toString());
        assertTrue(
                model.entities().stream()
                        .anyMatch(entity -> "273.15".equals(entity.properties().get("offset"))),
                model.entities().toString());
    }

    @Test
    void aComponentLocalDefinitionShadowsTheModelOne() throws Exception {
        var model = parse("units/cellml11-component-local-units.cellml");

        assertEquals(
                2,
                model.entities().stream()
                        .filter(entity ->
                                entity.type() == EntityType.UNIT && "pace".equals(entity.properties().get("display")))
                        .count(),
                model.entities().toString());
        assertTrue(
                model.entityRelationships().stream()
                        .anyMatch(relationship -> relationship.type().equals("HAS_UNIT")
                                && relationship.startEntityKey().equals("VARIABLE:slow/stride")
                                && relationship.endEntityKey().equals("UNIT:unit:2*metre^1*second^-1")),
                model.entityRelationships().toString());
        assertTrue(
                model.entityRelationships().stream()
                        .anyMatch(relationship -> relationship.type().equals("HAS_UNIT")
                                && relationship.startEntityKey().equals("VARIABLE:fast/stride")
                                && relationship.endEntityKey().equals("UNIT:unit:1*metre^1*second^-1")),
                model.entityRelationships().toString());
    }

    private static ParsedModel parse(String fixture) throws Exception {
        var reference = new ModelReference(
                Path.of(fixture).getFileName().toString(),
                URI.create("https://example.org/" + fixture),
                ModelType.CELLML);
        try (InputStream stream = CellmlUnitExtractionTest.class.getClassLoader().getResourceAsStream(fixture)) {
            return new CellmlModelParser().parse(new ModelSource(reference), stream);
        }
    }
}

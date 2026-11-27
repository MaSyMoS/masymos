package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class CellmlVersionMatrixTest {
    private static final String RESOURCE_DIR = "/cellml-versions/";

    @Test
    void parsesCellml10FixtureWithVersionDerivedFromNamespace() {
        var parsed = parse("cellml1_0.cellml");

        assertEquals("http://www.cellml.org/cellml/1.0#", parsed.properties().get("cellml.namespace"));
        assertEquals("1.0", parsed.properties().get("cellml.version"));
        assertHasTwoComponents(parsed);
    }

    @Test
    void parsesCellml11FixtureWithVersionDerivedFromNamespace() {
        var parsed = parse("cellml1_1.cellml");

        assertEquals("http://www.cellml.org/cellml/1.1#", parsed.properties().get("cellml.namespace"));
        assertEquals("1.1", parsed.properties().get("cellml.version"));
        assertHasTwoComponents(parsed);
    }

    @Test
    void parsesCellml20FixtureWithConnectionAttributesOnConnectionElement() {
        var parsed = parse("cellml2_0.cellml");

        assertEquals("2.0", parsed.properties().get("cellml.version"));
        assertEquals("http://www.cellml.org/cellml/2.0#", parsed.properties().get("cellml.namespace"));
        assertHasTwoComponents(parsed);
        assertTrue(parsed.entityRelationships().stream().anyMatch(relationship ->
                relationship.type().equals("IS_CONNECTED_TO")
        ));
    }

    @Test
    void theSupportedVersionsMatchTheFixturesThisTestProves() {
        var proved = List.of(
                new SupportedSchemaVersions.SchemaVersion("CELLML", "-", "1.0"),
                new SupportedSchemaVersions.SchemaVersion("CELLML", "-", "1.1"),
                new SupportedSchemaVersions.SchemaVersion("CELLML", "-", "2.0"));

        assertEquals(SupportedSchemaVersions.forFormat("CELLML"), proved);
    }

    private void assertHasTwoComponents(ParsedModel parsed) {
        List<ParsedModelEntity> components = parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.COMPONENT)
                .toList();
        assertEquals(2, components.size());
    }

    private boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    private ParsedModel parse(String resourceName) {
        var source = new ModelSource(new ModelReference(
                resourceName,
                URI.create("https://example.org/" + resourceName),
                ModelType.CELLML
        ));
        try (InputStream stream = stream(cellml(resourceName))) {
            return new CellmlModelParser().parse(source, stream);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private String cellml(String resourceName) {
        try (InputStream in = CellmlVersionMatrixTest.class.getResourceAsStream(RESOURCE_DIR + resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + RESOURCE_DIR + resourceName);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

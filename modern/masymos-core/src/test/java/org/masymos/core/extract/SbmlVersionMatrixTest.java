package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class SbmlVersionMatrixTest {
    private ModelSource source;

    @BeforeEach
    void createSource() {
        source = new ModelSource(new ModelReference(
                "matrix.xml",
                URI.create("https://example.org/matrix.xml"),
                ModelType.SBML
        ));
    }

    @ParameterizedTest(name = "parses SBML level {1} version {2} from {0}")
    @MethodSource("sbmlVersions")
    void parsesSupportedSbmlLevelsAndVersions(String resource, String level, String version) throws IOException {
        var parsed = new SbmlModelParser().parse(source, open(resource));

        assertEquals(level, parsed.properties().get("sbml.level"));
        assertEquals(version, parsed.properties().get("sbml.version"));
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                        entity.type() == ParsedModelEntity.EntityType.COMPARTMENT),
                () -> resource + " should contain a compartment entity");
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                        entity.type() == ParsedModelEntity.EntityType.SPECIES),
                () -> resource + " should contain a species entity");
        assertTrue(parsed.entities().stream().anyMatch(entity ->
                        entity.type() == ParsedModelEntity.EntityType.REACTION),
                () -> resource + " should contain a reaction entity");
    }

    @Test
    void theSupportedVersionsMatchTheFixturesThisTestProves() {
        var proved = new ArrayList<SupportedSchemaVersions.SchemaVersion>();
        sbmlVersions().forEach(arguments -> proved.add(new SupportedSchemaVersions.SchemaVersion(
                "SBML", (String) arguments.get()[1], (String) arguments.get()[2])));

        assertEquals(SupportedSchemaVersions.forFormat("SBML"), proved);
    }

    private static Stream<Arguments> sbmlVersions() {
        return Stream.of(
                Arguments.of("level1v1.xml", "1", "1"),
                Arguments.of("sbml-l1v2.xml", "1", "2"),
                Arguments.of("sbml-l2v2.xml", "2", "2"),
                Arguments.of("level2v4.xml", "2", "4"),
                Arguments.of("level3v1.xml", "3", "1"),
                Arguments.of("level3v2.xml", "3", "2")
        );
    }

    private InputStream open(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sbml-versions/" + resource)) {
            byte[] bytes = Objects.requireNonNull(in, () -> "missing fixture " + resource).readAllBytes();
            return new ByteArrayInputStream(bytes);
        }
    }
}

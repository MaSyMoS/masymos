package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class SedmlVersionMatrixTest {
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("sedml-level1v1.xml", "1"),
            new Fixture("sedml-level1v2.xml", "2"),
            new Fixture("sedml-level1v3.xml", "3"),
            new Fixture("sedml-level1v4.xml", "4"),
            new Fixture("sedml-level1v5.xml", "5")
    );

    /**
     * {@code sedml-level1v2.xml} is SED-ML test-suite case 00001, written by phraSED-ML 0.5beta
     * (2015-12-01), retrieved unmodified on 2026-11-13 from
     * {@code https://raw.githubusercontent.com/SED-ML/sedml-test-suite/master/cases/00001/00001-sedml-sbml.xml}.
     * It declares {@code level="1" version="2"} and its own namespace
     * {@code http://sed-ml.org/sed-ml/level1/version2}; the model it references
     * ({@code 00001-sbml.xml}) is not needed to parse the document, so only the SED-ML file is
     * committed.
     *
     * <p>{@code sedml-level1v5.xml} is the Lorenz example shipped with the Level 1 Version 5
     * specification itself: {@code SED-ML/sed-ml}, path
     * {@code specification/level-1-version-5/examples/lorenz-sbml/lorenz.xml}, retrieved unmodified on
     * 2026-11-16 (sha256 {@code 7EC2D7A9…FD4C0}, identical on a second download), namespace
     * {@code http://sed-ml.org/sed-ml/level1/version5}. It is the only fixture that exercises the V5
     * addition — an algorithm declared with twelve {@code algorithmParameter kisaoID} entries — which
     * is why it replaced the synthetic five-version stub (DATA-4).
     */
    @Test
    void theSupportedVersionsMatchTheFixturesThisTestProves() {
        var proved = FIXTURES.stream()
                .map(fixture -> new SupportedSchemaVersions.SchemaVersion("SEDML", "1", fixture.version()))
                .toList();

        assertEquals(SupportedSchemaVersions.forFormat("SEDML"), proved);
    }

    @Test
    void parsesSupportedSedmlLevel1Versions() {
        for (Fixture fixture : FIXTURES) {
            var parsed = new SedmlModelParser().parse(
                    new ModelSource(new ModelReference(
                            fixture.fileName(),
                            URI.create("https://example.org/" + fixture.fileName()),
                            ModelType.SEDML
                    )),
                    stream("/sedml-versions/" + fixture.fileName())
            );

            assertEquals("1", parsed.properties().get("sedml.level"), fixture.fileName());
            assertEquals(fixture.version(), parsed.properties().get("sedml.version"), fixture.fileName());

            assertTrue(hasEntity(parsed, ParsedModelEntity.EntityType.SEDML_MODELREFERENCE), fixture.fileName());
            assertTrue(hasEntity(parsed, ParsedModelEntity.EntityType.SEDML_TASK), fixture.fileName());
            assertTrue(hasEntity(parsed, ParsedModelEntity.EntityType.SEDML_SIMULATION), fixture.fileName());
            assertTrue(hasEntity(parsed, ParsedModelEntity.EntityType.SEDML_DATAGENERATOR), fixture.fileName());
        }
    }

    /**
     * The V5 algorithm reaches the graph as {@code simKisao}, and the algorithm <em>parameters</em> do
     * not reach it at all: modelling them is a new capability, so this assertion is the definition of
     * done for whoever adds it.
     */
    @Test
    void readsTheV5AlgorithmKisaoButNotItsAlgorithmParameters() {
        var parsed = new SedmlModelParser().parse(
                new ModelSource(new ModelReference(
                        "sedml-level1v5.xml",
                        URI.create("https://example.org/sedml-level1v5.xml"),
                        ModelType.SEDML
                )),
                stream("/sedml-versions/sedml-level1v5.xml")
        );

        var simulation = parsed.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SEDML_SIMULATION)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no simulation entity in " + parsed.entities()));

        assertEquals("KISAO:0000019", simulation.properties().get("simKisao"),
                "the simulation's algorithm id must reach the graph: " + simulation.properties());
        assertTrue(parsed.entities().stream()
                        .flatMap(entity -> entity.properties().values().stream())
                        .noneMatch(value -> value.contains("KISAO:0000211")),
                "an algorithmParameter id must not be modelled yet: " + parsed.entities());
    }

    private boolean hasEntity(ParsedModel parsed, ParsedModelEntity.EntityType type) {
        return parsed.entities().stream().anyMatch(entity -> entity.type() == type);
    }

    private InputStream stream(String resource) {
        var stream = SedmlVersionMatrixTest.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing fixture resource " + resource);
        }
        return stream;
    }

    private record Fixture(String fileName, String version) {}
}

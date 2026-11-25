package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * Parses unmodified, curated model files from BioModels.
 *
 * <p>The files under {@code src/test/resources/biomodels} were downloaded on 2026-11-11 from
 * {@code https://www.ebi.ac.uk/biomodels/model/download/<modelId>?filename=<file>}. The directory
 * names carry the SBML level/version stated in the BioModels file description. These tests run
 * offline; the fixtures are committed.
 */
class BiomodelsRealWorldTest {

    @Test
    void parsesCuratedSbmlLevel2Version1Model() throws Exception {
        var parsed = parseSbml("biomodels/L2V1/BIOMD0000000100.xml", "BIOMD0000000100.xml");

        assertEquals("2", parsed.properties().get("sbml.level"));
        assertEquals("1", parsed.properties().get("sbml.version"));
        assertRealModel(parsed);
    }

    @Test
    void parsesCuratedSbmlLevel2Version4Model() throws Exception {
        var parsed = parseSbml("biomodels/L2V4/BIOMD0000000001.xml", "BIOMD0000000001.xml");

        assertEquals("2", parsed.properties().get("sbml.level"));
        assertEquals("4", parsed.properties().get("sbml.version"));
        assertRealModel(parsed);
    }

    @Test
    void parsesCuratedSbmlLevel3Version1Model() throws Exception {
        var parsed = parseSbml("biomodels/L3V1/MODEL1507180035.xml", "MODEL1507180035.xml");

        assertEquals("3", parsed.properties().get("sbml.level"));
        assertEquals("1", parsed.properties().get("sbml.version"));
        assertRealModel(parsed);
    }

    @Test
    void parsesCuratedSedmlForLevel2Version1Model() throws Exception {
        assertRealSedml(parseSedml("biomodels/L2V1/BIOMD0000000100.sedml", "BIOMD0000000100.sedml"));
    }

    @Test
    void parsesCuratedSedmlForLevel2Version4Model() throws Exception {
        assertRealSedml(parseSedml("biomodels/L2V4/BIOMD0000000001.sedml", "BIOMD0000000001.sedml"));
    }

    @Test
    void curatedModelsProduceReferentiallyIntactGraphs() throws Exception {
        var fixtures = java.util.Map.of(
                "biomodels/L2V1/BIOMD0000000100.xml", "BIOMD0000000100.xml",
                "biomodels/L2V4/BIOMD0000000001.xml", "BIOMD0000000001.xml",
                "biomodels/L3V1/MODEL1507180035.xml", "MODEL1507180035.xml"
        );

        for (var entry : fixtures.entrySet()) {
            var graph = new ParsedModelGraphMapper().toGraph(parseSbml(entry.getKey(), entry.getValue()));
            var nodeKeys = graph.nodes().stream().map(ExtractedGraphNode::key).collect(java.util.stream.Collectors.toSet());

            var dangling = graph.relationships().stream()
                    .filter(relationship -> !nodeKeys.contains(relationship.startKey())
                            || !nodeKeys.contains(relationship.endKey()))
                    .map(relationship -> relationship.startKey() + "-[" + relationship.type() + "]->" + relationship.endKey())
                    .toList();
            assertTrue(dangling.isEmpty(), entry.getValue() + " has dangling relationships: " + dangling);

            var connected = graph.relationships().stream()
                    .flatMap(relationship -> java.util.stream.Stream.of(relationship.startKey(), relationship.endKey()))
                    .collect(java.util.stream.Collectors.toSet());
            var orphans = nodeKeys.stream().filter(key -> !connected.contains(key)).toList();
            assertTrue(orphans.isEmpty(), entry.getValue() + " has orphan nodes: " + orphans);
        }
    }

    private static void assertRealModel(ParsedModel parsed) {
        assertFalse(parsed.entities().isEmpty(), "curated model must yield entities");
        assertTrue(parsed.entities().stream().anyMatch(entity -> entity.type() == EntityType.COMPARTMENT),
                "curated model must yield compartments");
        assertTrue(parsed.entities().stream().anyMatch(entity -> entity.type() == EntityType.SPECIES),
                "curated model must yield species");
        assertTrue(parsed.entities().stream().anyMatch(entity -> entity.type() == EntityType.REACTION),
                "curated model must yield reactions");
        assertFalse(parsed.annotations().isEmpty(), "curated models carry RDF annotations");

        assertEquals(
                Integer.parseInt(parsed.properties().get("sbml.species")),
                parsed.entities().stream().filter(entity -> entity.type() == EntityType.SPECIES).count(),
                "declared species count must match extracted species entities"
        );
        assertEquals(
                Integer.parseInt(parsed.properties().get("sbml.reactions")),
                parsed.entities().stream().filter(entity -> entity.type() == EntityType.REACTION).count(),
                "declared reaction count must match extracted reaction entities"
        );
    }

    private static void assertRealSedml(ParsedModel parsed) {
        assertEquals("1", parsed.properties().get("sedml.level"));
        // Both curated BioModels SED-ML files are Level 1 Version 4 — a version the synthetic
        // matrix (1, 3, 5) does not cover, which is exactly why real fixtures matter.
        assertEquals("4", parsed.properties().get("sedml.version"));
        assertTrue(parsed.entities().stream()
                        .anyMatch(entity -> entity.type() == EntityType.SEDML_MODELREFERENCE),
                "curated SED-ML must reference a model");
        assertTrue(parsed.entities().stream()
                        .anyMatch(entity -> entity.type() == EntityType.SEDML_TASK),
                "curated SED-ML must define a task");
    }

    private static ParsedModel parseSbml(String resource, String fileId) throws Exception {
        var source = new ModelSource(new ModelReference(
                fileId,
                URI.create("https://www.ebi.ac.uk/biomodels/model/download/" + fileId),
                ModelType.SBML
        ));
        try (InputStream stream = open(resource)) {
            return new SbmlModelParser().parse(source, stream);
        }
    }

    private static ParsedModel parseSedml(String resource, String fileId) throws Exception {
        var source = new ModelSource(new ModelReference(
                fileId,
                URI.create("https://www.ebi.ac.uk/biomodels/model/download/" + fileId),
                ModelType.SEDML
        ));
        try (InputStream stream = open(resource)) {
            return new SedmlModelParser().parse(source, stream);
        }
    }

    private static InputStream open(String resource) {
        var stream = BiomodelsRealWorldTest.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("missing test resource: " + resource);
        }
        return stream;
    }
}

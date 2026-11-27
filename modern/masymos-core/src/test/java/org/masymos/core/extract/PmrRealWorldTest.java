package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * Parses CellML and SED-ML files downloaded unmodified from the Physiome Model Repository (PMR,
 * {@code https://models.physiomeproject.org}), retrieved 2026-11-11. Unlike the curated fixtures
 * elsewhere in this package these files were never touched by us, so they exercise the parsers on
 * what PMR actually serves.
 *
 * <p>Fixtures, each verified byte-identical to a fresh download (SHA-256):
 * <ul>
 *   <li>{@code pmr/friel_1995/friel_1995.cellml} (18730 bytes), CellML 1.0, root
 *       {@code xmlns="http://www.cellml.org/cellml/1.0#"}: {@code
 *       https://models.physiomeproject.org/workspace/friel_1995/@@rawfile/5d02b7540693a17504c68de20aecbe41ea4ab624/friel_1995.cellml}</li>
 *   <li>{@code pmr/785/atrialmyofibroblast.cellml} (66753 bytes), CellML 2.0, root
 *       {@code xmlns="http://www.cellml.org/cellml/2.0#"} and no {@code version} attribute (the
 *       version is derived from the namespace): {@code
 *       https://models.physiomeproject.org/workspace/785/@@rawfile/203520b0e61a344db31677d3f9f950d85471e8f1/atrialmyofibroblast.cellml}</li>
 *   <li>{@code pmr/c6b/GLUT2_BG.cellml} (18319 bytes), CellML 1.1, root
 *       {@code xmlns="http://www.cellml.org/cellml/1.1#"}: {@code
 *       https://models.physiomeproject.org/workspace/c6b/@@rawfile/7b4250346394811908c2379e4f3cfdb25384c109/GLUT2_BG.cellml}</li>
 *   <li>{@code pmr/c6b/GLUT2_BG.sedml} (27090 bytes), SED-ML level 1 version 3, declared as
 *       {@code <sedML level="1" version="3" xmlns="http://sed-ml.org/sed-ml/level1/version3">}: {@code
 *       https://models.physiomeproject.org/workspace/c6b/@@rawfile/7b4250346394811908c2379e4f3cfdb25384c109/GLUT2_BG.sedml}</li>
 * </ul>
 *
 * <p>The three CellML files cover three different language versions, which no synthetic fixture set
 * in this module could claim of files actually published in a repository.
 */
class PmrRealWorldTest {

    private static final String FRIEL_1995 = "pmr/friel_1995/friel_1995.cellml";
    private static final String ATRIAL_MYOFIBROBLAST = "pmr/785/atrialmyofibroblast.cellml";
    private static final String GLUT2_BG = "pmr/c6b/GLUT2_BG.cellml";
    private static final String GLUT2_BG_SEDML = "pmr/c6b/GLUT2_BG.sedml";

    private static final String CELLML_1_0_NAMESPACE = "http://www.cellml.org/cellml/1.0#";
    private static final String CELLML_1_1_NAMESPACE = "http://www.cellml.org/cellml/1.1#";
    private static final String CELLML_2_0_NAMESPACE = "http://www.cellml.org/cellml/2.0#";

    private static final String PMR_BASE = "https://models.physiomeproject.org/workspace/";
    private static final String FRIEL_1995_URL = PMR_BASE + "friel_1995/@@rawfile/"
            + "5d02b7540693a17504c68de20aecbe41ea4ab624/friel_1995.cellml";
    private static final String ATRIAL_MYOFIBROBLAST_URL = PMR_BASE + "785/@@rawfile/"
            + "203520b0e61a344db31677d3f9f950d85471e8f1/atrialmyofibroblast.cellml";
    private static final String GLUT2_BG_URL = PMR_BASE + "c6b/@@rawfile/"
            + "7b4250346394811908c2379e4f3cfdb25384c109/GLUT2_BG.cellml";

    /** Source URL of every fixture, used as the model reference so a parsed model names where it came from. */
    private static final Map<String, String> SOURCE_URLS = Map.of(
            FRIEL_1995, FRIEL_1995_URL,
            ATRIAL_MYOFIBROBLAST, ATRIAL_MYOFIBROBLAST_URL,
            GLUT2_BG, GLUT2_BG_URL
    );

    @Test
    void parsesPmrCellml10Model() throws Exception {
        var parsed = parseCellml(FRIEL_1995, "friel_1995.cellml");

        assertEquals("1.0", parsed.properties().get("cellml.version"));
        assertEquals(CELLML_1_0_NAMESPACE, parsed.properties().get("cellml.namespace"));
        assertHasComponentsAndVariables(parsed, FRIEL_1995);
    }

    @Test
    void parsesPmrCellml20Model() throws Exception {
        var parsed = parseCellml(ATRIAL_MYOFIBROBLAST, "atrialmyofibroblast.cellml");

        assertEquals("2.0", parsed.properties().get("cellml.version"));
        assertEquals(CELLML_2_0_NAMESPACE, parsed.properties().get("cellml.namespace"));
        assertHasComponentsAndVariables(parsed, ATRIAL_MYOFIBROBLAST);
    }

    @Test
    void parsesPmrCellml11Model() throws Exception {
        var parsed = parseCellml(GLUT2_BG, "GLUT2_BG.cellml");

        assertEquals("1.1", parsed.properties().get("cellml.version"));
        assertEquals(CELLML_1_1_NAMESPACE, parsed.properties().get("cellml.namespace"));
        assertHasComponentsAndVariables(parsed, GLUT2_BG);
    }

    @Test
    void parsesPmrSedmlFileWithModelReference() throws Exception {
        var parsed = parseSedml();

        assertFalse(parsed.properties().get("sedml.level").isBlank(), "SED-ML level must be recorded");
        assertEquals("1", parsed.properties().get("sedml.level"));
        assertEquals("3", parsed.properties().get("sedml.version"));
        var modelReferences = parsed.entities().stream()
                .filter(entity -> entity.type() == EntityType.SEDML_MODELREFERENCE)
                .toList();
        assertFalse(modelReferences.isEmpty(), "SED-ML file must declare at least one model reference");
        assertTrue(modelReferences.stream().anyMatch(entity ->
                        "GLUT2_BG.cellml".equals(entity.properties().get("modelSource"))),
                "model reference must point at the CellML file published next to it");
    }

    @Test
    void pmrFriel1995Cellml10ModelProducesReferentiallyIntactGraph() throws Exception {
        assertReferentiallyIntact(parseCellml(FRIEL_1995, "friel_1995.cellml"));
    }

    @Test
    void pmrAtrialMyofibroblastCellml20ModelProducesReferentiallyIntactGraph() throws Exception {
        assertReferentiallyIntact(parseCellml(ATRIAL_MYOFIBROBLAST, "atrialmyofibroblast.cellml"));
    }

    @Test
    void pmrGlut2Cellml11ModelProducesReferentiallyIntactGraph() throws Exception {
        assertReferentiallyIntact(parseCellml(GLUT2_BG, "GLUT2_BG.cellml"));
    }

    private static void assertHasComponentsAndVariables(ParsedModel parsed, String fixture) {
        assertFalse(parsed.properties().get("cellml.version").isBlank(), fixture + ": version must be recorded");
        assertFalse(parsed.properties().get("cellml.namespace").isBlank(), fixture + ": namespace must be recorded");
        assertTrue(parsed.entities().stream().anyMatch(entity -> entity.type() == EntityType.COMPONENT),
                fixture + ": PMR model must yield components");
        assertTrue(parsed.entities().stream().anyMatch(entity -> entity.type() == EntityType.VARIABLE),
                fixture + ": PMR model must yield variables");
    }

    /**
     * Same contract as {@link CellmlRealWorldTest#realCellmlModelProducesReferentiallyIntactGraph()}:
     * every relationship endpoint must be a mapped node, and every node must participate in at least
     * one relationship.
     */
    private static void assertReferentiallyIntact(ParsedModel parsed) {
        var graph = new ParsedModelGraphMapper().toGraph(parsed);
        var nodeKeys = graph.nodes().stream().map(ExtractedGraphNode::key).collect(Collectors.toSet());
        assertFalse(nodeKeys.isEmpty(), "graph of a PMR model must not be empty");

        var dangling = graph.relationships().stream()
                .filter(relationship -> !nodeKeys.contains(relationship.startKey())
                        || !nodeKeys.contains(relationship.endKey()))
                .map(relationship -> relationship.startKey() + "-[" + relationship.type() + "]->" + relationship.endKey())
                .toList();
        assertTrue(dangling.isEmpty(), "dangling relationships: " + dangling);

        var connected = graph.relationships().stream()
                .flatMap(relationship -> Stream.of(relationship.startKey(), relationship.endKey()))
                .collect(Collectors.toSet());
        var orphans = nodeKeys.stream().filter(key -> !connected.contains(key)).toList();
        assertTrue(orphans.isEmpty(), "orphan nodes: " + orphans);
    }

    private static ParsedModel parseCellml(String fixture, String fileId) throws Exception {
        var reference = new ModelReference(
                fileId,
                URI.create(SOURCE_URLS.get(fixture)),
                ModelType.CELLML
        );
        try (InputStream stream = open(fixture)) {
            return new CellmlModelParser().parse(new ModelSource(reference), stream);
        }
    }

    private static ParsedModel parseSedml() throws Exception {
        var reference = new ModelReference(
                "GLUT2_BG.sedml",
                URI.create("https://models.physiomeproject.org/workspace/c6b/"),
                ModelType.SEDML
        );
        try (InputStream stream = open(GLUT2_BG_SEDML)) {
            return new SedmlModelParser().parse(new ModelSource(reference), stream);
        }
    }

    private static InputStream open(String fixture) {
        InputStream stream = PmrRealWorldTest.class.getClassLoader().getResourceAsStream(fixture);
        if (stream == null) {
            throw new IllegalStateException("missing test resource: " + fixture);
        }
        return stream;
    }
}

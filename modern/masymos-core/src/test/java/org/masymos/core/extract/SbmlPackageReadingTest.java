package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.sbml.jsbml.SBMLReader;
import org.sbml.jsbml.ext.fbc.FBCConstants;
import org.sbml.jsbml.ext.fbc.FBCModelPlugin;
import org.sbml.jsbml.ext.fbc.GeneProduct;

/**
 * CORP-6: a Level 3 package element must not stop the read. JSBML maps a namespace without a
 * registered package parser to its generic XML reader, so the package's elements become opaque
 * {@code XMLNode}s; an {@code <annotation>} nested inside one of them then aborts the entire
 * document with {@code ClassCastException: XMLNode cannot be cast to Annotation} inside
 * {@code SBMLReader.readXMLFromXMLEventReader}. A curated BioModels archive
 * ({@code BIOMD0000001081}) holds exactly that shape on its FBC gene products, so the corpus lane
 * cannot go green without the package parsers on the classpath.
 */
class SbmlPackageReadingTest {

    @Test
    void readsADocumentThatAnnotatesAnFbcGeneProduct() throws Exception {
        var parsed = parse("sbml-packages/sbml-l3v1-fbc-gene-product-annotation.xml");

        assertEquals("fbc_demo", parsed.modelId());
        assertTrue(
                parsed.entities().stream()
                        .anyMatch(entity -> entity.id().equals("s1")),
                parsed.entities().toString());
    }

    /**
     * Pins the reason the first test passes: the FBC element is a parsed {@code GeneProduct}, not an
     * opaque {@code XMLNode}. Drop the package jars and this fails with JSBML's
     * "package namespace or name 'fbc' is unknown" instead of turning green.
     */
    @Test
    void readsTheGeneProductAsAParsedPackageObjectWithItsAnnotation() throws Exception {
        String xml;
        try (InputStream stream =
                SbmlPackageReadingTest.class.getClassLoader()
                        .getResourceAsStream("sbml-packages/sbml-l3v1-fbc-gene-product-annotation.xml")) {
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        var document = new org.sbml.jsbml.xml.stax.SBMLReader().readSBMLFromString(xml);
        var fbc = (FBCModelPlugin) document.getModel().getPlugin(FBCConstants.shortLabel);
        GeneProduct geneProduct = fbc.getListOfGeneProducts().get("G1");

        assertEquals("g1", geneProduct.getLabel());
        assertTrue(geneProduct.getAnnotationString().contains("P0A9J8"), geneProduct.getAnnotationString());
    }

    /**
     * Measured 2026-11-20 (CORP-8) on BioModels `BIOMD0000000899`, a CellDesigner-tooled Level 2 model:
     * the document aborted with `ClassCastException: XMLNode cannot be cast to Annotation` at
     * `SBMLReader.readXMLFromXMLEventReader:851`. A scan of that document's annotations by parent
     * element names the trigger - six of them sit inside a `celldesigner:species`, a namespace JSBML
     * has no reading parser for and no package jar can supply, so the document is retried once without
     * those elements (`UnsupportedSbmlNamespaces`, decision 43) and the model is kept.
     */
    @Test
    void readsADocumentWhoseCoreAnnotationSitsInsideAnUnparsedNamespace() throws Exception {
        var parsed = parse("sbml-packages/sbml-l2v4-celldesigner-species-annotation.xml");

        assertEquals("celldesigner_demo", parsed.modelId());
        assertTrue(
                parsed.entities().stream().anyMatch(entity -> entity.id().equals("s1")),
                parsed.entities().toString());
    }

    /**
     * Pins the reason the test above passes: JSBML itself cannot read this document, so it is the
     * removal of the unparsed namespace and not a forgiving fixture that makes the parse succeed.
     */
    @Test
    void jsbmlAloneCannotReadThatDocument() throws Exception {
        String xml;
        try (InputStream stream =
                SbmlPackageReadingTest.class.getClassLoader()
                        .getResourceAsStream("sbml-packages/sbml-l2v4-celldesigner-species-annotation.xml")) {
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        var failure = assertThrows(
                ClassCastException.class,
                () -> new org.sbml.jsbml.xml.stax.SBMLReader().readSBMLFromString(xml));
        assertTrue(failure.getMessage().contains("XMLNode"), failure.getMessage());
    }

    /**
     * CORP-9 (row CORP7-12): a document JSBML can only read after the namespace-stripping retry must
     * say so. Before the change nothing recorded the retry, so the corpus lane counted this document
     * as fully ingested and a reader could not tell that its CellDesigner annotations were never
     * indexed (decision 43).
     */
    @Test
    void theRetriedReadSaysSo() throws Exception {
        var parsed = parse("sbml-packages/sbml-l2v4-celldesigner-species-annotation.xml");

        assertEquals("true", parsed.properties().get("sbml.reducedRead"), parsed.properties().toString());
        assertTrue(
                parsed.properties().get("sbml.droppedNamespaces").contains("celldesigner"),
                parsed.properties().toString());
    }

    /**
     * The other half of the marker: a document JSBML reads in one pass loses nothing, so it carries
     * neither key. Without this a parser that marked every document would still pass the test above.
     */
    @Test
    void aOnePassReadCarriesNoMarker() throws Exception {
        var parsed = parse("sbml-packages/sbml-l3v1-fbc-gene-product-annotation.xml");

        assertFalse(parsed.properties().containsKey("sbml.reducedRead"), parsed.properties().toString());
        assertFalse(parsed.properties().containsKey("sbml.droppedNamespaces"), parsed.properties().toString());
    }

    /**
     * The marker has to reach the graph, and on the {@code Model} node: the document node already
     * carries every parsed property, so a marker only there would be invisible to a model lookup.
     */
    @Test
    void theModelNodeCarriesTheMarkerToo() throws Exception {
        var reduced = new ParsedModelGraphMapper().toGraph(
                parse("sbml-packages/sbml-l2v4-celldesigner-species-annotation.xml"));
        var onePass = new ParsedModelGraphMapper().toGraph(
                parse("sbml-packages/sbml-l3v1-fbc-gene-product-annotation.xml"));

        assertEquals("true", modelNode(reduced).properties().get("reducedRead"), modelNode(reduced).properties().toString());
        assertFalse(modelNode(onePass).properties().containsKey("reducedRead"),
                modelNode(onePass).properties().toString());
    }

    private static ExtractedGraphNode modelNode(ExtractedModelGraph graph) {
        return graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("graph has no model node"));
    }

    /**
     * CORP-13: the crash is a whole {@code <annotation>} nested inside an annotation, not the standard
     * RDF the annotation carries. Measured 2026-11-20 on `BIOMD0000000899`, where CellDesigner and COPASI
     * write a second, tool-owned annotation inside the model's own.
     */
    @Test
    void jsbmlAloneCannotReadANestedAnnotation() throws Exception {
        var xml = fixture("sbml-packages/sbml-l2v4-nested-annotation.xml");

        var failure = assertThrows(RuntimeException.class, () -> new SBMLReader().readSBMLFromString(xml));

        assertTrue(failure.getMessage().contains("XMLNode"), failure.getMessage());
    }

    /**
     * The two halves of that measurement: the nested block goes (and is counted), the standard MIRIAM
     * resource directly in the model's annotation stays - which is what a reduced read used to lose.
     */
    @Test
    void dropsTheNestedAnnotationAndKeepsTheStandardOne() throws Exception {
        var parsed = parse("sbml-packages/sbml-l2v4-nested-annotation.xml");

        assertEquals("true", parsed.properties().get("sbml.reducedRead"), parsed.properties().toString());
        assertEquals("1", parsed.properties().get("sbml.droppedNestedAnnotations"), parsed.properties().toString());
        assertFalse(parsed.properties().containsKey("sbml.droppedNamespaces"),
                "nothing was dropped for its namespace: " + parsed.properties());
        var resources = parsed.annotations().stream().flatMap(a -> a.resources().stream()).toList();
        assertTrue(
                resources.stream().anyMatch(resource -> resource.uri().contains("BIOMD0000000899")),
                "the standard MIRIAM resource must survive: " + resources);
        assertTrue(
                resources.stream().noneMatch(resource -> resource.uri().contains("Copasi")),
                "the nested block's resource must be gone: " + resources);
    }

    private static ParsedModel parse(String fixture) throws Exception {
        var reference = new ModelReference(
                Path.of(fixture).getFileName().toString(),
                URI.create("https://example.org/" + fixture),
                ModelType.SBML);
        try (InputStream stream = SbmlPackageReadingTest.class.getClassLoader().getResourceAsStream(fixture)) {
            return new SbmlModelParser().parse(new ModelSource(reference), stream);
        }
    }

    private static String fixture(String name) throws Exception {
        try (InputStream stream = SbmlPackageReadingTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
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

    private static ParsedModel parse(String fixture) throws Exception {
        var reference = new ModelReference(
                Path.of(fixture).getFileName().toString(),
                URI.create("https://example.org/" + fixture),
                ModelType.SBML);
        try (InputStream stream = SbmlPackageReadingTest.class.getClassLoader().getResourceAsStream(fixture)) {
            return new SbmlModelParser().parse(new ModelSource(reference), stream);
        }
    }
}

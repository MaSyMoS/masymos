package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * FBC-2: an {@code fbc} gene product carries an id, a label and a MIRIAM annotation that
 * {@code CORP-9}'s keep-list already indexes, so it is the one package element whose identity a
 * search can use (decision 52). These four tests pin the read: the entity and its identity, the
 * association to the reaction it describes, the flattening of a boolean tree into one edge per
 * referenced gene product, and the absence of all of it in a Level 2 document, which has no
 * {@code fbc} package at all.
 */
class SbmlFbcGeneProductTest {

    @Test
    void readsGeneProductsWithTheirIdentity() throws Exception {
        var geneProducts = geneProducts(parse("sbml-packages/sbml-l3v1-fbc-associations.xml"));

        assertEquals(List.of("G1", "G2"), geneProducts.stream().map(ParsedModelEntity::id).toList());
        assertEquals(
                List.of("g1", "g2"),
                geneProducts.stream().map(entity -> entity.properties().get("label")).toList());
    }

    @Test
    void linksAnAssociationToTheReactionItDescribes() throws Exception {
        var relationships = associations(parse("sbml-packages/sbml-l3v1-fbc-associations.xml"));

        assertEquals(List.of("R1->G1"), relationships.stream().filter(edge -> edge.startsWith("R1->")).toList());
    }

    @Test
    void flattensAnAndTreeToEveryReferencedGeneProduct() throws Exception {
        var relationships = associations(parse("sbml-packages/sbml-l3v1-fbc-associations.xml"));

        // R2's association nests an <fbc:or> inside its <fbc:and> and names G1 twice; the walk
        // collects the leaves in document order and de-duplicates, so G1 yields one edge.
        assertEquals(List.of("R2->G1", "R2->G2"), relationships.stream().filter(edge -> edge.startsWith("R2->")).toList());
    }

    @Test
    void readsNoGeneProductFromALevelTwoDocument() throws Exception {
        assertEquals(List.of(), geneProducts(parse("sbml-versions/sbml-l2v2.xml")));
    }

    /**
     * A Level 2 root that declares the fbc namespace, and the measurement behind the parser's Level 3
     * guard (2026-11-24): {@code jsbml-fbc} 1.6.1 binds its {@code FBCModelPlugin} here - for the
     * level2 and the level3 namespace alike - but fills the gene-product list only under the Level 3
     * core namespace. So this document answers no entity either way, and the test pins the reader's
     * answer rather than the guard's line; removing the guard was measured not to change it. What it
     * would catch is a future library that starts filling the list.
     */
    @Test
    void readsNoGeneProductFromALevelTwoDocumentThatDeclaresFbc() throws Exception {
        var model = parse("sbml-packages/sbml-l2v4-fbc-declared.xml");

        assertEquals("fbc_on_level_2", model.modelId());
        assertEquals(List.of(), geneProducts(model));
    }

    /**
     * {@code FBC-4}, the cheaper half (decided with the user on 2026-11-25): {@code jsbml-fbc} 1.6.1
     * declares no {@code Not} type, so a {@code <fbc:not>} operand is dropped, the association answers
     * {@code null} and the reaction loses every edge. Reading the operand would mean a second parser for
     * the association tree; reporting the loss costs one count, so the document says it was read in
     * reduced form - the marker {@code CORP-9} uses for a stripped namespace - and names what went
     * missing.
     */
    @Test
    void reportsADroppedNotAssociationAsAReducedRead() throws Exception {
        var model = parse("sbml-packages/sbml-l3v1-fbc-not.xml");

        assertEquals("true", model.properties().get("sbml.reducedRead"), model.properties().toString());
        assertEquals("1", model.properties().get("sbml.droppedFbcNot"), model.properties().toString());
        assertEquals(List.of("R1->G1"), associations(model), "R2's association is the one JSBML drops");
    }

    /** The other direction: a document whose associations JSBML reads whole is not a reduced read. */
    @Test
    void doesNotReportAReducedReadWithoutANotAssociation() throws Exception {
        var model = parse("sbml-packages/sbml-l3v1-fbc-associations.xml");

        assertNull(model.properties().get("sbml.reducedRead"), model.properties().toString());
        assertNull(model.properties().get("sbml.droppedFbcNot"), model.properties().toString());
    }

    private static List<ParsedModelEntity> geneProducts(ParsedModel model) {
        return model.entities().stream()
                .filter(entity -> entity.type() == ParsedModelEntity.EntityType.SBML_GENE_PRODUCT)
                .toList();
    }

    /**
     * The {@code ASSOCIATED_WITH} edges as {@code <reactionId>-><geneProductId>}, in the order the
     * parser emitted them: document order is the contract, because it is the order a reader sees in
     * the {@code <fbc:and>}.
     */
    private static List<String> associations(ParsedModel model) {
        return model.entityRelationships().stream()
                .filter(relationship -> "ASSOCIATED_WITH".equals(relationship.type()))
                .map(relationship -> reactionId(relationship.startEntityKey())
                        + "->"
                        + geneProductId(relationship.endEntityKey()))
                .toList();
    }

    private static String reactionId(String entityKey) {
        return entityKey.replace(ParsedModelEntity.EntityType.REACTION.name() + ":", "");
    }

    private static String geneProductId(String entityKey) {
        return entityKey.replace(ParsedModelEntity.EntityType.SBML_GENE_PRODUCT.name() + ":", "");
    }

    private static ParsedModel parse(String fixture) throws Exception {
        var reference = new ModelReference(
                Path.of(fixture).getFileName().toString(),
                URI.create("https://example.org/" + fixture),
                ModelType.SBML);
        try (InputStream stream = SbmlFbcGeneProductTest.class.getClassLoader().getResourceAsStream(fixture)) {
            return new SbmlModelParser().parse(new ModelSource(reference), stream);
        }
    }
}

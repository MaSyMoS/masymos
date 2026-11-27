package org.masymos.core.spi;

import java.util.Optional;

/**
 * Reads the ontology traversal the index supports: term lookup, root lookup, depth and similarity.
 *
 * <p>The port exists because the traversal used to be reachable only from its own unit test. It is a
 * read-only view over the graph an {@code OwlOntologyIndexingService} wrote; nothing here writes.
 *
 * <p>Every operation is relative to the ontology's label, which the parser derives from the declared
 * ontology name. An empty result means something specific per method and the callers must not collapse
 * those meanings: "not indexed" is a 404, "no path" is not.
 */
public interface OntologyTraversal {
    /**
     * One term by id.
     *
     * @param ontology the ontology label, e.g. {@code SBOOntology}
     * @param id the term id as the graph stores it
     * @return the term, or empty when no term with that id is indexed for that ontology
     */
    Optional<OntologyTerm> term(String ontology, String id);

    /**
     * The ontology's root term, whose id is {@code owl:<ontology>}.
     *
     * @return the root term, or empty when the ontology is not indexed
     */
    Optional<OntologyTerm> root(String ontology);

    /**
     * The {@code isA} distance from a term to the ontology root.
     *
     * @param maxDepth the longest path to walk; the caller bounds it, because the value reaches a
     *     variable-length Cypher pattern
     * @return the depth, or empty when the term exists but no path to the root was found within
     *     {@code maxDepth} — which is not the same as the term being unknown
     */
    Optional<Integer> depth(String ontology, String id, int maxDepth);

    /**
     * The similarity of two terms, through their lowest common ancestor.
     *
     * @return the similarity, or empty when the two terms share no ancestor or one of them is not
     *     indexed
     */
    Optional<OntologySimilarity> similarity(String ontology, String first, String second, int maxDepth);
}

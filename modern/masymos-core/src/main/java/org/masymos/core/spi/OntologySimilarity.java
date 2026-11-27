package org.masymos.core.spi;

/**
 * The similarity of two ontology terms, computed over the asserted {@code isA} edges only.
 *
 * <p>No reasoner is applied (see {@code OWL-1}), so every number here is relative to the graph as it
 * was indexed: a subclass relation that only an inference would produce does not contribute.
 *
 * @param pathLength the shortest undirected {@code isA} distance between the two terms
 * @param lowestCommonAncestor the id of the lowest term both are descendants of
 * @param lowestCommonAncestorDepth the {@code isA} distance from that ancestor to the ontology root
 * @param similarity the legacy formula's score over those three numbers, between 0 and 1
 */
public record OntologySimilarity(
        int pathLength,
        String lowestCommonAncestor,
        int lowestCommonAncestorDepth,
        double similarity
) {
}

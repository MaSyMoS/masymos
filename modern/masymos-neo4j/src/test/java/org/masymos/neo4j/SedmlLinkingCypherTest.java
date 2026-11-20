package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Shape guards for the SED-ML link queries.
 *
 * <p>These tests read the generated Cypher; they cannot execute it. The behaviour - that a real
 * SED-ML/SBML pair is linked and a look-alike URI is not - is proven end to end by
 * {@code ExternalNeo4jGraphWriteIT}, which indexes both documents and then queries the
 * {@code REFERENCES_SIMULATION_MODEL} edge. Keeping the two apart is deliberate: a test that
 * re-implements the predicate in Java would pass while the Cypher was wrong.
 */
class SedmlLinkingCypherTest {
    @Test
    void preparesLegacySedmlModelReferenceLinkingCypher() {
        var write = SedmlLinkingCypher.linkModelReferences();

        assertTrue(write.cypher().contains("SEDML_MODELREFERENCE"));
        assertTrue(write.cypher().contains("SBML_MODEL"));
        assertTrue(write.cypher().contains("REFERENCES_SIMULATION_MODEL"));
        assertTrue(write.parameters().isEmpty());
    }

    @Test
    void preparesLegacySedmlObservationLinkingCypher() {
        var sbmlWrite = SedmlLinkingCypher.linkSbmlObservedVariables();
        var cellmlWrite = SedmlLinkingCypher.linkCellmlObservedVariables();

        assertTrue(sbmlWrite.cypher().contains("HAS_SPECIES"));
        assertTrue(sbmlWrite.cypher().contains("OBSERVES"));
        assertTrue(cellmlWrite.cypher().contains("HAS_COMPONENT"));
        assertTrue(cellmlWrite.cypher().contains("HAS_VARIABLE"));
        assertTrue(cellmlWrite.cypher().contains("OBSERVES"));
    }

    @Test
    void returnsRelationshipCountFromEveryLinkQuery() {
        assertTrue(SedmlLinkingCypher.linkModelReferences().cypher().contains("relationshipCount"));
        assertTrue(SedmlLinkingCypher.linkSbmlObservedVariables().cypher().contains("relationshipCount"));
        assertTrue(SedmlLinkingCypher.linkCellmlObservedVariables().cypher().contains("relationshipCount"));
    }

    /**
     * SEDML-5: the link pass must not fall back on a URI suffix. The old predicate compared the last
     * 15 characters, so two different models whose URIs ended alike both matched.
     */
    @Test
    void linkModelReferencesDoesNotJoinOnUriSuffix() {
        var cypher = SedmlLinkingCypher.linkModelReferences().cypher();

        assertFalse(cypher.contains("right("), "no right() truncation may remain: " + cypher);
        assertFalse(cypher.contains("ENDS WITH"), "a suffix test under another name is still a suffix test: " + cypher);
        assertFalse(cypher.contains("15"), "no 15-character fragment may be compared: " + cypher);
    }

    /**
     * The join is an explicit identity: the source as the document declares or resolves it (an exact
     * match covers archive entries and plain URIs), or the MIRIAM pair {@code (prefix, identifier)}
     * that lets a {@code urn:miriam:} reference reach an identifiers.org annotation resource.
     */
    @Test
    void linkModelReferencesJoinsOnExactSourceOrTheMiriamIdentity() {
        var cypher = SedmlLinkingCypher.linkModelReferences().cypher();

        assertTrue(cypher.contains("source = target"), "an exact source/URI match must still link: " + cypher);
        assertTrue(cypher.contains("'urn:miriam:'"), "the MIRIAM URN form must be recognised: " + cypher);
        assertTrue(cypher.contains("://identifiers.org/"), "the identifiers.org form must be recognised: " + cypher);
        assertTrue(cypher.contains("sourceIdentity = targetIdentity"), "the identity pair must be compared whole: " + cypher);
        // A resolved source is preferred; the declared source is the fallback.
        assertTrue(cypher.contains("coalesce(ref.resolvedSource, ref.modelSource)"), cypher);
    }
}

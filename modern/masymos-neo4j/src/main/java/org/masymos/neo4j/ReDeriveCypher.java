package org.masymos.neo4j;

import java.util.List;

/**
 * The statements the re-derive scan reads ({@code OPN-7}). Read-only by construction: nothing in this
 * class writes, which is the point of the first slice - the scan measures before anything repairs.
 *
 * <p>The counts are per label set, because that is how the writer groups its nodes: a label cannot be
 * a parameter, so the label chain is spliced in and the number of statements is bounded by the
 * writer's vocabulary, never by the document.
 */
final class ReDeriveCypher {
    private ReDeriveCypher() {
    }

    /**
     * How many nodes of one label set this document owns, and how many of those predate
     * {@code derivedKey}. A node without the property cannot be recognised by a repair, which is why
     * the second number is reported on its own rather than folded into a shortfall.
     */
    static String ownedNodeState(List<String> labels) {
        return "MATCH (n" + chain(labels) + ") WHERE n.ownerUid = $uid "
                + "RETURN count(n) AS present, count(CASE WHEN n.derivedKey IS NULL THEN 1 END) AS withoutKey";
    }

    /** The label chain the writer also splices into its own statement: {@code (:LABEL_ONE:LABEL_TWO)}. */
    static String chain(List<String> labels) {
        var chain = new StringBuilder();
        for (var label : labels) {
            chain.append(':').append(label);
        }
        return chain.toString();
    }
}

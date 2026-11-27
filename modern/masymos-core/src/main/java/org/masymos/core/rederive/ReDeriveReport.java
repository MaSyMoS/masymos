package org.masymos.core.rederive;

import java.util.Map;

/**
 * What one re-derive scan found ({@code OPN-7}, 2026-11-26). The scan is read-only: it re-parses the
 * source document the graph stores and reports what the current mapper yields that the graph does not
 * hold. Nothing is written, so the report is also the list a writer would be verified against.
 *
 * <p>"Stale" has two forms and the report keeps them apart, because they have different causes. A
 * <strong>node shortfall</strong> means the mapper produces more nodes of a label set than the graph
 * holds for that document - the write path gained a construct since it was indexed. A
 * <strong>missing {@code derivedKey}</strong> means the document's owned nodes predate the property a
 * re-derive merges on, so every such document needs one pass before a writer could recognise its nodes
 * at all.
 *
 * @param examined documents with a stored source this run looked at
 * @param stale documents with a shortfall or a missing {@code derivedKey}
 * @param current documents whose graph already holds everything the mapper yields
 * @param truncated documents skipped because their stored source is a truncated copy
 * @param unsupported documents whose model type this scan does not re-parse (OWL and P2M today)
 * @param unparseable documents whose stored source the parser refused
 * @param documentsWithoutSource documents the graph knows but this scan cannot read at all
 * @param remaining documents after the page position this run stopped at
 * @param missingByLabel the node shortfall merged over the page, keyed by the label set's sorted names
 * @param missingDerivedKeys owned nodes over the page that carry no {@code derivedKey}
 * @param nextCursor the {@code uid} to resume after, or {@code null} when the page was not cut
 * @param resumed whether this run started from a cursor rather than the beginning
 */
public record ReDeriveReport(
        int examined,
        int stale,
        int current,
        int truncated,
        int unsupported,
        int unparseable,
        int documentsWithoutSource,
        int remaining,
        Map<String, Integer> missingByLabel,
        int missingDerivedKeys,
        String nextCursor,
        boolean resumed,
        int applied
) {
    /** The default bound of one scan: 500 documents with a stored source. */
    public static final int DEFAULT_LIMIT = 500;

    /** {@code limit} value meaning "no bound"; a whole-corpus run from a worker thread. */
    public static final int UNBOUNDED = 0;

    public ReDeriveReport {
        missingByLabel = missingByLabel == null ? Map.of() : Map.copyOf(missingByLabel);
    }

    /**
     * A read-only scan's report: nothing was rewritten, so {@code applied} is zero.
     *
     * @param applied documents this run actually repaired; always zero for a scan
     */
    public ReDeriveReport(
            int examined,
            int stale,
            int current,
            int truncated,
            int unsupported,
            int unparseable,
            int documentsWithoutSource,
            int remaining,
            Map<String, Integer> missingByLabel,
            int missingDerivedKeys,
            String nextCursor,
            boolean resumed) {
        this(
                examined,
                stale,
                current,
                truncated,
                unsupported,
                unparseable,
                documentsWithoutSource,
                remaining,
                missingByLabel,
                missingDerivedKeys,
                nextCursor,
                resumed,
                0);
    }
}

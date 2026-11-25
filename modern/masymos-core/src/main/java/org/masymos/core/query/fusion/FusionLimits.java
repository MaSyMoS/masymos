package org.masymos.core.query.fusion;

/**
 * What the fusion pipeline had to leave out of one request: the three bounds that shorten an answer
 * without an error ({@code C03-2}, closed 2026-11-23; the third one {@code C03-5}, closed 2026-11-24).
 *
 * <ul>
 *   <li>{@code rowLookupTruncated} - the document-URI to model-row lookup answered its own row bound
 *       while more rows matched, so some versions never reached their ranker and a resource hit could
 *       fuse into fewer versions than the graph holds.
 *   <li>{@code documentUrisTruncated} - a resource ranker had more <em>distinct</em> document URIs than
 *       its budget allows, so the ones past it contributed no URI to the lookup. A ranker that fills the
 *       budget exactly is not a cut: the flag needs a URI this ranker would have contributed and could
 *       not, which is why a complete answer never reports it.
 *   <li>{@code rankerDepthTruncated} - a ranker held more rows than the per-ranker depth
 *       ({@code RankFusionQueryService.MAX_PER_RANKER_TOP}) <em>and</em> the collapsed answer is shorter
 *       than {@code top}, so the depth - not a store that simply holds that many files - is a candidate
 *       explanation for the short page. One probe row beyond the depth is what proves the ranker had
 *       more to give, and it is discarded, so the depth stays the answer's ceiling (decision 51).
 * </ul>
 *
 * <p>All three describe the <em>request</em>, not a row: every hit of one response carries the same
 * values, exactly as {@code ModelSearchResult.matchedFields} and {@code indexSource} describe their
 * request. They are not the stored-document flag ({@code ModelRow.documentTruncated}, STO-2): that one
 * says the graph holds a truncated copy of a model's source, these say the search did not look at
 * everything the request named.
 *
 * <p>{@code rankerDepthTruncated} is the one of the three that states a <em>candidate</em> rather than a
 * certainty, and the difference is deliberate. Two other shapes were available: report the probe
 * unconditionally (certain, but it fires on a complete answer - at {@code top = 150} on a store of 300
 * files the depth is reached and all 150 asked rows are answered) or not at all (the defect). The rule
 * that remains is the one {@code C03-2}'s review established for the URI budget: a bound that left
 * nothing out of the <em>answer</em> must not be reported. What that costs is stated: with several
 * versions per file the depth can cut rows belonging to files already in the answer, so {@code true}
 * means "the depth was reached and the answer is short", never "more files existed" - telling those
 * apart would mean not having a depth.
 *
 * <p>One blind spot is deliberate: an answer with no hits carries no value at all, so a request that a
 * bound shortened <em>and</em> that fused into nothing reports nothing. There is no record to hang it
 * on, and the empty list already says "nothing fused", which is the fact that matters to a caller.
 */
public record FusionLimits(
        boolean rowLookupTruncated, boolean documentUrisTruncated, boolean rankerDepthTruncated) {

    /** No bound of the request cut anything. */
    public static final FusionLimits NONE = new FusionLimits(false, false, false);

    /** Whether any bound shortened the answer. */
    public boolean anyTruncated() {
        return rowLookupTruncated || documentUrisTruncated || rankerDepthTruncated;
    }
}

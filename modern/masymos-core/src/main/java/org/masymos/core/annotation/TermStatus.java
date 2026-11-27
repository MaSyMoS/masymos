package org.masymos.core.annotation;

/**
 * The lifecycle status an ontology provider reported for a term, as <em>metadata</em> of a resolved
 * definition.
 *
 * <p><strong>D11: obsolescence is metadata, never a failure.</strong>
 *
 * <ul>
 *   <li>A merged id is a <em>valid</em> annotation. A curated model that cites
 *       {@code GO:0043241} names a term that existed and was merged into {@code GO:0032984}; the
 *       citation must still yield searchable text, so the term stage follows the successor and
 *       indexes the successor's published text under the cited id.
 *   <li>The cited id is <strong>never rewritten</strong>. The definition's {@link TermKey} stays the
 *       id the curated model wrote; {@link #replacedBy()} records what it became. Rewriting would
 *       silently edit the source model's meaning.
 *   <li>An obsolete or merged id is therefore <strong>never</strong> a negative: it is never
 *       {@link TermDefinition.FailureClass#DEFINITIVE}, so the cache never stores it as a failure and
 *       the next run does not have to rediscover it.
 * </ul>
 *
 * <p>{@link #source()} names the provider that reported the status (for example {@code "ols4"}), so a
 * later reader can tell a provider's claim from an inference.
 *
 * @param obsolete whether the cited term is obsolete (measured: OLS4's {@code isObsolete}, confirmed
 *     by {@code owl#deprecated})
 * @param replacedBy the successor as a <em>curie</em> ({@code GO:0032984}, {@code CHEBI:18357}), or
 *     {@code null} when the term was not merged or the provider named no successor
 * @param reason the obsolescence reason IRI the provider reported (for example
 *     {@code http://purl.obolibrary.org/obo/IAO_0000227}, "terms merged"), or {@code null}
 * @param source the provider that reported this status, for example {@code "ols4"} - {@code null}
 *     exactly when there is no status to attribute (see {@link #none()})
 */
public record TermStatus(boolean obsolete, String replacedBy, String reason, String source) {

    public TermStatus {
        if (replacedBy != null && replacedBy.isBlank()) {
            replacedBy = null;
        }
        if (reason != null && reason.isBlank()) {
            reason = null;
        }
    }

    /**
     * "Nothing unusual reported": not obsolete, no successor, no reason, no source. Every definition
     * that is not obsolete carries this, so {@link TermDefinition} never has a {@code null} status.
     */
    public static TermStatus none() {
        return new TermStatus(false, null, null, null);
    }

    /** Whether a status is nothing to report, so a caller may leave it out of any rendering. */
    public boolean isNone() {
        return !obsolete && replacedBy == null && reason == null;
    }
}

package org.masymos.service.indexing;

import java.util.List;

/**
 * An index request that did not index every source (task {@code C04-1}, finding {@code GAP02-002b},
 * decision 49).
 *
 * <p>It is thrown <em>after</em> the run, never to abort it, and it carries the outcome of every source in
 * the request: the models that were written are named, so the answer to a failed request is never silent
 * about the work that succeeded. {@code ApiExceptionHandler} renders it as the documented {@code 502
 * partial-index} problem detail, whose {@code outcomes} property is this list.
 *
 * <p>Its message is the problem's {@code detail}: a count a caller can read without parsing the array, and
 * the first source that failed, because that is the one to look at first.
 */
public class PartialIndexException extends RuntimeException {

    private final transient List<SourceOutcome> outcomes;

    public PartialIndexException(List<SourceOutcome> outcomes) {
        super(summary(List.copyOf(outcomes)));
        this.outcomes = List.copyOf(outcomes);
    }

    /** Every source of the request exactly once, in request order. */
    public List<SourceOutcome> outcomes() {
        return outcomes;
    }

    private static String summary(List<SourceOutcome> outcomes) {
        var indexed = outcomes.stream()
                .filter(outcome -> outcome.status() == SourceOutcome.Status.INDEXED)
                .count();
        var failed = outcomes.stream()
                .filter(outcome -> outcome.status() == SourceOutcome.Status.FAILED)
                .count();
        var notAttempted = outcomes.size() - indexed - failed;
        var firstFailure = outcomes.stream()
                .filter(outcome -> outcome.status() == SourceOutcome.Status.FAILED)
                .map(outcome -> outcome.sourceUri().toString())
                .findFirst()
                .orElse("unknown source");
        var parts = new StringBuilder()
                .append(indexed).append(" of ").append(outcomes.size()).append(" sources were indexed")
                .append("; ").append(failed).append(" failed");
        if (notAttempted > 0) {
            parts.append(", ").append(notAttempted).append(" not attempted");
        }
        return parts.append("; first failure at ").append(firstFailure)
                .append(" - see the outcomes array for every source")
                .toString();
    }
}

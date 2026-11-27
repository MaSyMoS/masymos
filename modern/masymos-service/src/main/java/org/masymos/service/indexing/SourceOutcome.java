package org.masymos.service.indexing;

import java.net.URI;
import java.util.List;
import org.masymos.core.extract.IndexedModel;

/**
 * What one source of an index request did (task {@code C04-1}, finding {@code GAP02-002b}).
 *
 * <p>An index request is a list of sources and is <strong>not atomic</strong> across them: a document
 * that fails at position 51 has already written 1-50. Before this type the answer said nothing about
 * them, so a caller could neither retry the right subset nor know what the graph now holds (decision 49).
 * The three states are the whole vocabulary: {@link Status#INDEXED} carries the identifiers it wrote,
 * {@link Status#FAILED} carries the reason, and {@link Status#NOT_ATTEMPTED} exists because a store
 * outage ends the run - inventing failure reasons for sources nobody read would be worse than saying so.
 *
 * @param sourceUri the source URI exactly as the request named it, so a caller can match it to its input
 * @param status what happened to that source
 * @param documents the models the source wrote, in the run's order; empty unless it was indexed, and
 *     legitimately empty for an archive that carried only metadata entries
 * @param detail why the source failed, {@code null} unless it failed; never a driver or store message
 */
public record SourceOutcome(URI sourceUri, Status status, List<IndexedDocument> documents, String detail) {

    /** The three states of one source in one run. */
    public enum Status {
        INDEXED,
        FAILED,
        NOT_ATTEMPTED
    }

    /** One written document, by the two identifiers the index response already publishes. */
    public record IndexedDocument(String uid, String fileId) {}

    /**
     * The constant reason a store outage records. The driver's own message can name a bolt URI and these
     * responses are readable without a credential unless a shared secret is configured ({@code A04-03}),
     * so the outage is reported by the same constant the {@code 503} uses.
     */
    public static final String STORAGE_UNAVAILABLE_DETAIL = "the graph store is not available";

    /** The longest failure text this type publishes; a parser message can quote a whole document. */
    private static final int MAX_DETAIL_LENGTH = 500;

    /** A source every one of whose documents was written. */
    static SourceOutcome indexed(URI sourceUri, List<IndexedModel> models) {
        var documents = models.stream()
                .map(model -> new IndexedDocument(
                        model.identifier().uid(), model.identifier().fileId()))
                .toList();
        return new SourceOutcome(sourceUri, Status.INDEXED, documents, null);
    }

    /**
     * A source the run could not bring into the store. The detail names the failure's kind and its
     * message: a parser message already names the source and what was wrong with it, which is the only
     * actionable thing a caller gets from a failed document.
     */
    static SourceOutcome failed(URI sourceUri, Throwable failure) {
        return new SourceOutcome(sourceUri, Status.FAILED, List.of(), describe(failure));
    }

    /** A source the run never read because the store went away before its turn. */
    static SourceOutcome notAttempted(URI sourceUri) {
        return new SourceOutcome(sourceUri, Status.NOT_ATTEMPTED, List.of(), null);
    }

    private static String describe(Throwable failure) {
        var message = failure.getMessage() == null ? "" : ": " + failure.getMessage().strip();
        var described = failure.getClass().getSimpleName() + message;
        return described.length() <= MAX_DETAIL_LENGTH ? described : described.substring(0, MAX_DETAIL_LENGTH);
    }
}

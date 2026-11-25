package org.masymos.service.api;

import java.io.IOException;

/**
 * An {@code IOException} raised by the local write path (task T-017, finding A04-02).
 *
 * <p>A failed write is not an upstream outage: answering {@code 502 model-source-unavailable} for a
 * full disk or a read-only directory tells the client to retry later against a source that is fine.
 * The advice therefore maps {@code IOException} to {@code 502} - a source that could not be
 * <em>read</em> - and this type to {@code 500 write-failed}, so the two causes are told apart by the
 * exception rather than by inspecting a message.
 *
 * <p>No shipped code raises it today: the only write path of a document is the graph repository, whose
 * failure is {@link StorageUnavailableException} ({@code 503}). It is the contract for the first
 * component that writes to the filesystem, and {@code ApiExceptionHandlerTest} pins the mapping so the
 * branch cannot rot into {@code 502} unnoticed.
 */
public class WriteFailedException extends IOException {
    public WriteFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public WriteFailedException(String message) {
        super(message);
    }
}

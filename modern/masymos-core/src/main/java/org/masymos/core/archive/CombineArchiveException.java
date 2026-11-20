package org.masymos.core.archive;

/**
 * Signals that a COMBINE archive (OMEX) could not be read: it is malformed, oversized,
 * missing its manifest, or contains an unsafe entry location.
 */
public final class CombineArchiveException extends RuntimeException {
    public CombineArchiveException(String message) {
        super(message);
    }

    public CombineArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}

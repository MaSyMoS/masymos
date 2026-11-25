package org.masymos.core.model;

import java.util.Objects;

/**
 * What one delete removed: the documents (versions) and the nodes that went with them, including
 * the annotation nodes purged because nothing referenced them any more.
 *
 * <p>Legacy reported the deleted uid, a node count and a relationship count. The relationship count
 * is not reproduced: it is a display value, and computing it doubles the deletion query. The uid is
 * absent for a delete by {@code fileId}, where several versions go at once.
 */
public record ModelDeletionResult(String uid, String fileId, int documents, long nodes) {
    public ModelDeletionResult {
        if (documents < 0) {
            throw new IllegalArgumentException("documents must not be negative");
        }
        if (nodes < 0) {
            throw new IllegalArgumentException("nodes must not be negative");
        }
        Objects.requireNonNull(fileId, "fileId");
    }
}

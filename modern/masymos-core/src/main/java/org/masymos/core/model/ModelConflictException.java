package org.masymos.core.model;

/**
 * A model operation refused because it would contradict the stored state: a delete whose
 * confirmation parameters do not match the document, or an insert that must keep {@code fileId}
 * unique while a document with that {@code fileId} already exists.
 *
 * <p>Legacy answered both with {@code ok=false} and a message instead of an exception; modern keeps
 * the refusal and reports it as HTTP 409, so a client cannot mistake it for success.
 */
public final class ModelConflictException extends RuntimeException {
    private ModelConflictException(String message) {
        super(message);
    }

    public static ModelConflictException guardViolation(String message) {
        return new ModelConflictException(message);
    }

    public static ModelConflictException duplicateFileId(String fileId) {
        return new ModelConflictException("a model document with fileId " + fileId + " already exists");
    }
}

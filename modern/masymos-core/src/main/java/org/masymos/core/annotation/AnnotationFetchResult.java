package org.masymos.core.annotation;

/** Outcome of one HTTP fetch for an annotation candidate URL. */
public record AnnotationFetchResult(
        String url,
        int statusCode,
        String contentType,
        String body,
        String failureReason,
        String truncationReason
) {
    public AnnotationFetchResult(String url, int statusCode, String contentType, String body, String failureReason) {
        this(url, statusCode, contentType, body, failureReason, null);
    }

    public static AnnotationFetchResult failure(String url, String failureReason) {
        return new AnnotationFetchResult(url, 0, null, "", failureReason);
    }

    public boolean succeeded() {
        return failureReason == null && statusCode >= 200 && statusCode < 300;
    }

    /** Whether the transport abandoned the body at the policy's byte cap. */
    public boolean truncated() {
        return truncationReason != null;
    }
}

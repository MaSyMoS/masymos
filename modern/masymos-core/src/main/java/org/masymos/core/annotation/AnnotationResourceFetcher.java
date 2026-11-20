package org.masymos.core.annotation;

/** Fetches one candidate annotation URL. Implementations must never throw to the caller. */
@FunctionalInterface
public interface AnnotationResourceFetcher {
    AnnotationFetchResult fetch(String url);
}

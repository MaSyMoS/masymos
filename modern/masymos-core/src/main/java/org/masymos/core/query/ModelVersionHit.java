package org.masymos.core.query;

/** One matching version inside a version-grouped model result; {@code versionId} is null when the model carries no version label. */
public record ModelVersionHit(String uid, String versionId, double score) {
}

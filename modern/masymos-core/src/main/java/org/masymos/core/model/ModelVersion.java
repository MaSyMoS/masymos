package org.masymos.core.model;

import java.util.Objects;

/**
 * One stored document version of a model file.
 *
 * <p>"Latest" is a graph fact, not a comparison of labels: legacy MaSyMoS defined the current
 * version as the document with no outgoing {@code HAS_SUCCESSOR} relationship, and a version label
 * such as {@code 2014-01-01} or a commit hash cannot be ordered. {@code versionId} is therefore
 * carried through untouched and may be absent.
 */
public record ModelVersion(String uid, String fileId, String versionId, Long legacyUid) {
    public ModelVersion {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(fileId, "fileId");
    }
}

package org.masymos.core.model;

import java.util.Objects;

/**
 * Identity of a stored model document.
 *
 * <p>{@code uid} is the canonical identifier and stays a string UUID. {@code legacyUid} is the
 * numeric identifier legacy MaSyMoS minted from its {@code GlobalUniqueId} counter; it is kept so a
 * client holding an old numeric uid can still resolve a document (decision D1). {@code versionId}
 * is the external version label, absent when the source carried none.
 */
public record ModelIdentifier(String uid, String fileId, String versionId, Long legacyUid) {
    public ModelIdentifier {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(fileId, "fileId");
    }

    public ModelIdentifier(String uid, String fileId) {
        this(uid, fileId, null, null);
    }
}

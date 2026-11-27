package org.masymos.core.query.fusion;

import java.net.URI;
import java.util.Objects;

/**
 * One entry of one ranker before collation. {@code uid} identifies the model version and is the
 * collation key; {@code fileId} is the file the version belongs to. {@code documentUri} is
 * optional and {@code null} for a metadata-only node.
 */
public record RankedModel(String uid, String fileId, URI documentUri, double score) {
    public RankedModel {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(fileId, "fileId");
    }
}

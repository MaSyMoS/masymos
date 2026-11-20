package org.masymos.core.model;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;

/**
 * A stored document together with the source content captured when it was indexed (STO-2).
 *
 * <p>The content is {@code null} when the document was stored without one (a document inserted by
 * reference only). {@code originalBytes} is the size before any truncation, and {@code truncated}
 * says whether the stored copy is shorter than the source, so a client is never left guessing.
 */
public record StoredDocument(
        String uid,
        String fileId,
        String versionId,
        Long legacyUid,
        String content,
        String encoding,
        long originalBytes,
        boolean truncated,
        Map<String, String> metadata
) {
    public static final String DEFAULT_ENCODING = "UTF-8";

    public StoredDocument {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(fileId, "fileId");
        encoding = encoding == null || encoding.isBlank() ? DEFAULT_ENCODING : encoding;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (originalBytes < 0) {
            throw new IllegalArgumentException("originalBytes must not be negative");
        }
    }

    public boolean hasContent() {
        return content != null;
    }

    /** The stored content in the encoding it was captured with; empty when nothing was stored. */
    public byte[] contentBytes() {
        return content == null ? new byte[0] : content.getBytes(Charset.forName(encoding));
    }
}

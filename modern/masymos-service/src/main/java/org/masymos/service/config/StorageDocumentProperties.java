package org.masymos.service.config;

import org.masymos.core.extract.SourceDocument;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How much of a source document is kept in the graph store.
 *
 * <p>Legacy MaSyMoS stored the document location and never a size-limited copy, so there was
 * nothing to tune; the modern store keeps the content itself and therefore needs a ceiling. The
 * default is {@link SourceDocument#DEFAULT_MAX_BYTES} (10 MiB). A document above it is stored up to
 * the limit and flagged as truncated, never silently shortened (STO-2).
 */
@ConfigurationProperties(prefix = "masymos.storage.document")
public record StorageDocumentProperties(Integer maxBytes) {
    public int maxBytesOrDefault() {
        return maxBytes == null ? SourceDocument.DEFAULT_MAX_BYTES : maxBytes;
    }
}

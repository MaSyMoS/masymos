package org.masymos.core.query.fusion;

import java.net.URI;

/**
 * One model version as the fusion pipeline hydrates it: the identity and document facts the model
 * queries already return, resolved either from a model hit or from an annotation, person or
 * publication hit's document source URI.
 *
 * <p>{@code documentUri} is {@code null} for a row whose document carries no source URI, and
 * {@code documentBytes} is {@code null} while the document is not stored. Nothing here is derived:
 * the values are exactly the flat model projection's.
 */
public record ModelRow(
        String uid,
        String fileId,
        String modelId,
        String modelName,
        URI documentUri,
        String versionId,
        boolean documentStored,
        Long documentBytes,
        boolean documentTruncated) {}

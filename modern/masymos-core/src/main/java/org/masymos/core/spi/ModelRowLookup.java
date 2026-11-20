package org.masymos.core.spi;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import org.masymos.core.query.fusion.ModelRow;

/**
 * Resolves document source URIs into model rows.
 *
 * <p>Annotation, person and publication hits carry the source URIs of the models they relate to;
 * the fusion pipeline needs the model rows behind those URIs to turn a resource hit into entries
 * for its ranker. One call over the whole union of URIs keeps the resolution to a single query.
 */
public interface ModelRowLookup {
    /**
     * Model rows for the given document source URIs.
     *
     * @param documentUris source URIs of documents; an empty collection returns an empty list
     * @return one row per matching model version, ordered by {@code uid}; URIs with no model are
     *     simply absent
     */
    List<ModelRow> byDocumentUris(Collection<URI> documentUris);
}

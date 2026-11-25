package org.masymos.core.spi;

import java.net.URI;
import java.util.Collection;
import org.masymos.core.query.fusion.ModelRows;

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
     * @param documentUris source URIs of documents; an empty collection returns
     *     {@link ModelRows#empty()}
     * @return one row per matching model version, ordered by {@code uid}, plus whether the lookup
     *     stopped at its own bound while more rows matched; URIs with no model are simply absent
     */
    ModelRows byDocumentUris(Collection<URI> documentUris);
}

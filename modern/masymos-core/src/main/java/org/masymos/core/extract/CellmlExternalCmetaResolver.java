package org.masymos.core.extract;

import java.util.List;

/**
 * Supplies external PMR {@code @@cmeta} for a CellML model.
 *
 * <p><b>External cmeta stays opt-in (CML-3 decision, 2026-11-13).</b> {@link CellmlModelParser}'s
 * no-argument constructor uses {@link #NONE}, so the default indexing path never fetches anything
 * from outside the document. The change of intent behind this decision: legacy fetched {@code
 * @@cmeta} for every model ({@code CellMLExtractor} did so unconditionally, any URL scheme, 10 s
 * timeout), which made an index run depend on a remote service and its availability. Modern keeps
 * the enrichment available but only when the caller passes a resolver explicitly to
 * {@code CellmlModelIndexingService.indexUris(uris, resolver)}; a caller that wants the legacy
 * behaviour passes {@link RemotePmrCellmlExternalCmetaResolver}. Opt-in is not a temporary state
 * pending a later default: an index run must stay reproducible from its inputs, and a document that
 * already carries its own RDF metadata must not silently gain a second, unreviewed description of
 * itself.
 *
 * <p>A caller that does switch it on gets the same contract as legacy - a resolver that cannot
 * reach its source returns an empty list rather than failing the model.
 */
@FunctionalInterface
public interface CellmlExternalCmetaResolver {
    /** The default: resolves nothing, fetches nothing. Keep this the default; see the type javadoc. */
    CellmlExternalCmetaResolver NONE = source -> List.of();

    List<CellmlExternalCmeta> resolve(ModelSource source);
}

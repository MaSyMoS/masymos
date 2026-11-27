package org.masymos.core.config;

import java.util.Objects;
import org.masymos.core.annotation.HttpAnnotationEgressPolicy;

/**
 * The one injection point an annotation service takes: the egress policy every annotation HTTP call
 * shares, and the two caps that bound a COMBINE archive read for an annotated model.
 *
 * <p>The package is new because no existing package owns both halves. {@link HttpAnnotationEgressPolicy}
 * lives in {@code org.masymos.core.annotation}, the archive caps in
 * {@code org.masymos.core.archive}; a record placed in either would make that package depend on the
 * other for no reason beyond a constructor convenience. A service that wires both (the annotation
 * enrichment of {@code AnnotationEnrichmentService}, the archive reader of {@code
 * CombineArchiveReader}) takes one instance instead of two sets of numbers, so a caller cannot hand
 * the two halves different values by accident.
 *
 * @param egress the egress policy for annotation HTTP calls; never {@code null}
 * @param archiveEntries how many members one archive may carry
 * @param manifestContentElements how many {@code <content>} elements one manifest may declare
 */
public record AnnotationServiceBounds(
        HttpAnnotationEgressPolicy egress,
        int archiveEntries,
        int manifestContentElements) {

    /**
     * How many members one archive may carry. Measured 2026-11-21 over the nine archives harvested
     * into {@code corpus/files} (72 files): the largest holds seven members, so 1,000 keeps two orders
     * of magnitude of headroom while bounding the read - the bind scan is
     * {@code O(content elements × members)}, so an uncapped count is quadratic in one document.
     */
    public static final int DEFAULT_ARCHIVE_ENTRIES = 1_000;

    /**
     * How many {@code <content>} elements one manifest may declare; 1,000 above the same measured
     * corpus, whose manifests mirror their members. This is the element half of that quadratic, and it
     * is counted before any element is bound.
     */
    public static final int DEFAULT_MANIFEST_CONTENT_ELEMENTS = 1_000;

    public AnnotationServiceBounds {
        Objects.requireNonNull(egress, "egress");
        if (archiveEntries < 1) {
            throw new IllegalArgumentException("archiveEntries must be at least 1: " + archiveEntries);
        }
        if (manifestContentElements < 1) {
            throw new IllegalArgumentException(
                    "manifestContentElements must be at least 1: " + manifestContentElements);
        }
    }

    /** The shipped defaults: the default egress policy and both measured archive caps. */
    public static AnnotationServiceBounds defaults() {
        return new AnnotationServiceBounds(
                HttpAnnotationEgressPolicy.defaults(),
                DEFAULT_ARCHIVE_ENTRIES,
                DEFAULT_MANIFEST_CONTENT_ELEMENTS);
    }
}

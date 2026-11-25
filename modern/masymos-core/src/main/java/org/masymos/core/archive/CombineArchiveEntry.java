package org.masymos.core.archive;

/**
 * One {@code <content>} declaration from an OMEX manifest.
 *
 * @param location archive-relative path of the entry, for example {@code models/becker1.sbml}
 * @param format the decoded format URI of the entry
 * @param master whether the manifest marks this entry as the archive's master (entry point)
 */
public record CombineArchiveEntry(String location, CombineArchiveFormat format, boolean master) {
    public CombineArchiveEntry {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("archive entry location must not be blank");
        }
        if (format == null) {
            throw new IllegalArgumentException("archive entry format must not be null: " + location);
        }
    }
}

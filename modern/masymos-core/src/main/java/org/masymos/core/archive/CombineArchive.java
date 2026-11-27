package org.masymos.core.archive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fully read COMBINE archive (OMEX): the manifest entries in manifest order plus the raw
 * bytes of every entry listed in the manifest.
 *
 * <p>The content arrays are owned, not copied, on the way in and out ({@code REL-002}): the reader
 * inflates each member once and hands that array over, and {@link #content(String)} answers that
 * same array, so one member costs one array for the life of the archive instead of one per read.
 * A caller must therefore treat a {@code content} result as read-only: mutating it changes what a
 * later read of the same location answers.</p>
 */
public final class CombineArchive {
    private final List<CombineArchiveEntry> entries;
    private final Map<String, byte[]> contentByLocation;

    /**
     * Takes ownership of {@code contentByLocation}: the arrays are stored as given, without the
     * defensive copy a foreign caller would need, because the reader is the only producer and drops
     * its own reference when it returns.
     */
    public CombineArchive(List<CombineArchiveEntry> entries, Map<String, byte[]> contentByLocation) {
        this.entries = List.copyOf(entries);
        var owned = new LinkedHashMap<String, byte[]>();
        contentByLocation.forEach((location, content) ->
                owned.put(location, content == null ? new byte[0] : content));
        this.contentByLocation = Map.copyOf(owned);
    }

    public List<CombineArchiveEntry> entries() {
        return entries;
    }

    /**
     * The raw bytes stored for {@code location}, if present. This is the stored array itself, not a
     * copy: read it, do not modify it.
     */
    public Optional<byte[]> content(String location) {
        return Optional.ofNullable(contentByLocation.get(location));
    }

    /** The entry marked {@code master="true"}; only meaningful when the manifest marks a single entry. */
    public Optional<CombineArchiveEntry> masterEntry() {
        return entries.stream().filter(CombineArchiveEntry::master).findFirst();
    }
}

package org.masymos.core.archive;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A fully read COMBINE archive (OMEX): the manifest entries in manifest order plus the raw
 * bytes of every entry listed in the manifest.
 */
public final class CombineArchive {
    private final List<CombineArchiveEntry> entries;
    private final Map<String, byte[]> contentByLocation;

    public CombineArchive(List<CombineArchiveEntry> entries, Map<String, byte[]> contentByLocation) {
        this.entries = List.copyOf(entries);
        var copy = new LinkedHashMap<String, byte[]>();
        contentByLocation.forEach((location, content) ->
                copy.put(location, content == null ? new byte[0] : Arrays.copyOf(content, content.length)));
        this.contentByLocation = Map.copyOf(copy);
    }

    public List<CombineArchiveEntry> entries() {
        return entries;
    }

    /** Returns a defensive copy of the raw bytes stored for {@code location}, if present. */
    public Optional<byte[]> content(String location) {
        var content = contentByLocation.get(location);
        return content == null ? Optional.empty() : Optional.of(Arrays.copyOf(content, content.length));
    }

    /** The entry marked {@code master="true"}; only meaningful when the manifest marks a single entry. */
    public Optional<CombineArchiveEntry> masterEntry() {
        return entries.stream().filter(CombineArchiveEntry::master).findFirst();
    }
}

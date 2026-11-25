package org.masymos.core.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.masymos.core.config.AnnotationServiceBounds;
import org.w3c.dom.Element;

/**
 * Reads a COMBINE archive (OMEX), a zip file whose root holds an {@code omexManifest}-shaped
 * {@code manifest.xml}.
 *
 * <p>The reader is deliberately defensive: the archive is bounded in size, the manifest is parsed
 * without DOCTYPE or external entity resolution, entry locations must stay inside the archive,
 * and zip members not listed in the manifest are ignored.</p>
 *
 * <p>Peak memory: the archive is read once and held once. Each member is inflated straight into the
 * array that {@link CombineArchive#content(String)} later hands out, so the reader keeps one array
 * per member (listed in the manifest or not) and the per-call copies are gone ({@code REL-002}). A
 * member whose declared size is usable is allocated at exactly that size; one that fills its array
 * exactly is probed with a single byte before growing, so an honest declaration costs no copy at
 * all, and a wrong one costs one growth or trim copy of that member. The entry and archive caps
 * bound the total.</p>
 */
public final class CombineArchiveReader {
    static final String MANIFEST_ENTRY = "manifest.xml";
    static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    /** How much a member's array grows by per step when the zip declares no usable size. */
    private static final int READ_CHUNK_BYTES = 8192;
    private static final String MANIFEST_NAMESPACE =
            "http://identifiers.org/combine.specifications/omex-manifest";
    /** The same URI published over https; BioModels ships this spelling (measured 2026-11-19). */
    private static final String MANIFEST_NAMESPACE_HTTPS =
            "https://identifiers.org/combine.specifications/omex-manifest";
    private static final String ROOT_LOCATION = ".";

    private final int maxArchiveEntries;
    private final int maxManifestContentElements;

    /**
     * A reader with the shipped caps. The two numbers come from {@link AnnotationServiceBounds}, the
     * one record an annotation service takes, so the reader cannot drift from the value the rest of
     * the service was wired with. Both are counted before a member is buffered or an element is bound.
     */
    public CombineArchiveReader() {
        this(AnnotationServiceBounds.defaults());
    }

    /** A reader whose two caps are the ones the service bounds carry. */
    public CombineArchiveReader(AnnotationServiceBounds bounds) {
        this(bounds.archiveEntries(), bounds.manifestContentElements());
    }

    /**
     * A reader with the two caps overridden. Package-private on purpose: only a test drives a document
     * past a cap without building one, and the shipped caps stay the only ones production reaches.
     */
    CombineArchiveReader(int maxArchiveEntries, int maxManifestContentElements) {
        this.maxArchiveEntries = maxArchiveEntries;
        this.maxManifestContentElements = maxManifestContentElements;
    }

    public CombineArchive read(InputStream stream) {
        if (stream == null) {
            throw new CombineArchiveException("archive stream must not be null");
        }
        var members = unzip(stream);
        // Some packers store the manifest as "./manifest.xml"; treat both as the root manifest.
        var manifest = members.get(MANIFEST_ENTRY);
        if (manifest == null) {
            manifest = members.get("./" + MANIFEST_ENTRY);
        }
        if (manifest == null) {
            throw new CombineArchiveException("archive does not contain a root " + MANIFEST_ENTRY);
        }
        var entries = manifestEntries(manifest, maxManifestContentElements);
        var bound = new ArrayList<CombineArchiveEntry>();
        var contentByLocation = new LinkedHashMap<String, byte[]>();
        for (var entry : entries) {
            var member = bindToMember(entry.location(), members);
            if (member == null) {
                // Nothing in the archive answers to the declared location. The entry keeps its place
                // in manifest order so the caller reports it exactly as before.
                bound.add(entry);
                continue;
            }
            bound.add(member.equals(entry.location())
                    ? entry
                    : new CombineArchiveEntry(member, entry.format(), entry.master()));
            contentByLocation.put(member, members.get(member));
        }
        return new CombineArchive(bound, contentByLocation);
    }

    /**
     * The zip member a manifest location names.
     *
     * <p>A manifest can name a member by a path the archive does not contain: an absolute one, or one
     * carrying the packer's own home directory - the three FROG archives on BioModels declare
     * {@code .//home/kramanlab/Tanisha/.../suppl_datasheet_s2_iCJ415.xml} while the member sits at the
     * archive root (CORP-8). When exactly one member carries the same file name, that is the member
     * the manifest means; two candidates are ambiguity and are never guessed at.</p>
     */
    private static String bindToMember(String location, Map<String, byte[]> members) {
        if (members.containsKey(location)) {
            return location;
        }
        var name = location.substring(location.lastIndexOf('/') + 1);
        if (name.isEmpty()) {
            return null;
        }
        String bound = null;
        for (var member : members.keySet()) {
            if (member.equals(name) || member.endsWith("/" + name)) {
                if (bound != null) {
                    return null;
                }
                bound = member;
            }
        }
        return bound;
    }

    private Map<String, byte[]> unzip(InputStream stream) {
        var members = new LinkedHashMap<String, byte[]>();
        var total = new long[] {0L};
        try (var zip = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || isDefaultZipEntry(entry.getName())) {
                    continue;
                }
                if (members.size() >= maxArchiveEntries) {
                    // Counted before the entry is buffered: the cap bounds memory and the bind scan,
                    // so it may not be paid for first. The message names the entry, so an operator
                    // knows which one was refused.
                    throw new CombineArchiveException("archive declares more than " + maxArchiveEntries
                            + " entries; entry " + (members.size() + 1) + " (" + entry.getName() + ") is refused");
                }
                // Some packers prefix members with "./" while the manifest does not (and vice
                // versa); the lookup is by name, so both sides are normalised.
                var memberName = entry.getName().startsWith("./") ? entry.getName().substring(2) : entry.getName();
                members.put(memberName, inflate(zip, entry, total));
            }
        } catch (CombineArchiveException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CombineArchiveException("failed to read COMBINE archive: " + exception.getMessage(), exception);
        }
        return members;
    }

    /**
     * Inflates one member into the array that is handed on, so its bytes exist once. The array is
     * sized from the declared entry size when the zip carries a usable one; a member that fills its
     * array exactly is probed with one byte before growing, so an honest declaration costs no copy
     * and only a wrong one costs a growth or trim copy of that member.
     */
    private static byte[] inflate(ZipInputStream zip, ZipEntry entry, long[] total) throws IOException {
        var content = new byte[initialCapacity(entry.getSize())];
        var length = 0;
        while (true) {
            if (length == content.length) {
                var probe = new byte[1];
                var more = zip.read(probe, 0, 1);
                if (more < 0) {
                    return content;
                }
                total[0] += more;
                requireWithinCaps(entry.getName(), (long) length + more, total[0]);
                content = Arrays.copyOf(content, grownCapacity((long) length + more));
                content[length++] = probe[0];
                continue;
            }
            var read = zip.read(content, length, content.length - length);
            if (read < 0) {
                return length == content.length ? content : Arrays.copyOf(content, length);
            }
            total[0] += read;
            requireWithinCaps(entry.getName(), (long) length + read, total[0]);
            length += read;
        }
    }

    /** The first array size: the declared entry size when the zip carries one, else one read chunk. */
    private static int initialCapacity(long declaredSize) {
        return declaredSize >= 0 && declaredSize <= MAX_ENTRY_BYTES ? (int) declaredSize : READ_CHUNK_BYTES;
    }

    /**
     * Grows past {@code written} so the next read has room, and leaves one byte of slack past the
     * entry cap so an oversized member trips the cap instead of filling the array silently.
     */
    private static int grownCapacity(long written) {
        var next = Math.max(written * 2, written + READ_CHUNK_BYTES);
        return (int) Math.min(next, MAX_ENTRY_BYTES + 1);
    }

    private static void requireWithinCaps(String name, long entryBytes, long totalBytes) {
        if (entryBytes > MAX_ENTRY_BYTES) {
            throw new CombineArchiveException("archive entry exceeds " + MAX_ENTRY_BYTES + " bytes: " + name);
        }
        if (totalBytes > MAX_ARCHIVE_BYTES) {
            throw new CombineArchiveException("archive exceeds " + MAX_ARCHIVE_BYTES + " bytes of content");
        }
    }

    private static boolean isDefaultZipEntry(String name) {
        return name.isBlank() || ".".equals(name) || "__MACOSX".equals(name)
                || name.startsWith("__MACOSX/");
    }

    private static boolean isManifestNamespace(String namespace) {
        return MANIFEST_NAMESPACE.equals(namespace) || MANIFEST_NAMESPACE_HTTPS.equals(namespace);
    }

    private static ArrayList<CombineArchiveEntry> manifestEntries(byte[] manifest, int maxContentElements) {
        var entries = new ArrayList<CombineArchiveEntry>();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(manifest));
            var root = document.getDocumentElement();
            if (root == null) {
                throw new CombineArchiveException("manifest.xml does not contain an omexManifest root element");
            }
            if ("manifest".equals(root.getLocalName())) {
                // BioModels ships the manifest wrapped: <manifest><omexManifest>...</omexManifest></manifest>.
                var wrapped = root.getElementsByTagNameNS("*", "omexManifest");
                if (wrapped.getLength() != 1) {
                    throw new CombineArchiveException(
                            "manifest.xml has a <manifest> wrapper without exactly one omexManifest");
                }
                root = (Element) wrapped.item(0);
            }
            if (!"omexManifest".equals(root.getLocalName())) {
                throw new CombineArchiveException("manifest.xml does not contain an omexManifest root element");
            }
            if (root.getNamespaceURI() != null && !isManifestNamespace(root.getNamespaceURI())) {
                throw new CombineArchiveException("manifest.xml uses an unexpected namespace: " + root.getNamespaceURI());
            }
            var contentElements = root.getElementsByTagNameNS("*", "content");
            if (contentElements.getLength() > maxContentElements) {
                throw new CombineArchiveException("manifest.xml declares more than " + maxContentElements
                        + " <content> elements; element " + (maxContentElements + 1) + " is refused");
            }
            for (var index = 0; index < contentElements.getLength(); index++) {
                var element = (Element) contentElements.item(index);
                if (element.getParentNode() == null || element.getParentNode() != root) {
                    throw new CombineArchiveException("manifest.xml nests <content> outside omexManifest");
                }
                var location = element.getAttribute("location").trim();
                if (location.isBlank()) {
                    throw new CombineArchiveException("manifest.xml declares <content> without a location");
                }
                if (ROOT_LOCATION.equals(location) || "./".equals(location)) {
                    continue;
                }
                // A manifest may spell one member several ways the zip cannot hold: "./x", "a//b", and
                // "a\b" from a packer on Windows. The 2026-11-23 corpus draw measured all three - 28 of
                // its 96 entry locations (C02D-1) - and a backslash that survived would reach the entry
                // path, the fileId and the Document identity, so it is normalised once, here, before
                // anything is derived from it.
                var normalised = normaliseLocation(location);
                var candidate = java.nio.file.Path.of(normalised);
                // An absolute location can never name a zip member, but it is usually a wrong spelling
                // of one that is in the archive (BioModels BIOMD0000001085 lists /home/... paths,
                // CORP-4; the FROG archives list .//home/..., CORP-8), so it stays and is bound by base
                // name in read(). A "../" escape is a manifest error and still fails here.
                if (!(candidate.isAbsolute() || candidate.getRoot() != null)) {
                    requireSafeLocation(normalised);
                }
                entries.add(new CombineArchiveEntry(
                        normalised,
                        CombineArchiveFormat.parse(element.getAttribute("format")),
                        "true".equalsIgnoreCase(element.getAttribute("master").trim())));
            }
            return entries;
        } catch (CombineArchiveException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CombineArchiveException("failed to parse manifest.xml: " + exception.getMessage(), exception);
        }
    }

    /**
     * One spelling for an entry location, so that the value the reader publishes is a path a zip can
     * hold: {@code ./x} loses its dot segment, a doubled separator collapses, and a backslash becomes a
     * separator. The corpus draw of 2026-11-23 measured all three across four BioModels archives
     * ({@code C02D-1}); a spelling that survived would become the entry path, the derived {@code fileId}
     * and the {@code Document} identity, and one document would look like two or three.
     */
    private static String normaliseLocation(String location) {
        var slashes = location.replace('\\', '/');
        var withoutDotSegment = slashes.startsWith("./") ? slashes.substring(2) : slashes;
        return withoutDotSegment.replaceAll("/{2,}", "/");
    }

    private static void requireSafeLocation(String location) {        var normalized = location.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new CombineArchiveException("manifest entry location must be archive-relative: " + location);
        }
        for (var segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                throw new CombineArchiveException("manifest entry location escapes the archive: " + location);
            }
        }
    }
}

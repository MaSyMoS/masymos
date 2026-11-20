package org.masymos.core.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Reads a COMBINE archive (OMEX), a zip file whose root holds an {@code omexManifest}-shaped
 * {@code manifest.xml}.
 *
 * <p>The reader is deliberately defensive: the archive is bounded in size, the manifest is parsed
 * without DOCTYPE or external entity resolution, entry locations must stay inside the archive,
 * and zip members not listed in the manifest are ignored.</p>
 */
public final class CombineArchiveReader {
    static final String MANIFEST_ENTRY = "manifest.xml";
    static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final String MANIFEST_NAMESPACE =
            "http://identifiers.org/combine.specifications/omex-manifest";
    /** The same URI published over https; BioModels ships this spelling (measured 2026-11-19). */
    private static final String MANIFEST_NAMESPACE_HTTPS =
            "https://identifiers.org/combine.specifications/omex-manifest";
    private static final String ROOT_LOCATION = ".";

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
        var entries = manifestEntries(manifest);
        var contentByLocation = new LinkedHashMap<String, byte[]>();
        for (var entry : entries) {
            var content = members.get(entry.location());
            if (content != null) {
                contentByLocation.put(entry.location(), content);
            }
        }
        return new CombineArchive(entries, contentByLocation);
    }

    private static Map<String, byte[]> unzip(InputStream stream) {
        var members = new LinkedHashMap<String, byte[]>();
        var total = 0L;
        try (var zip = new ZipInputStream(stream)) {
            var buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || isDefaultZipEntry(entry.getName())) {
                    continue;
                }
                var out = new ByteArrayOutputStream();
                int read;
                var entryBytes = 0L;
                while ((read = zip.read(buffer)) > 0) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > MAX_ENTRY_BYTES) {
                        throw new CombineArchiveException(
                                "archive entry exceeds " + MAX_ENTRY_BYTES + " bytes: " + entry.getName());
                    }
                    if (total > MAX_ARCHIVE_BYTES) {
                        throw new CombineArchiveException(
                                "archive exceeds " + MAX_ARCHIVE_BYTES + " bytes of content");
                    }
                    out.write(buffer, 0, read);
                }
                // Some packers prefix members with "./" while the manifest does not (and vice
                // versa); the lookup is by name, so both sides are normalised.
                var memberName = entry.getName().startsWith("./") ? entry.getName().substring(2) : entry.getName();
                members.put(memberName, out.toByteArray());
            }
        } catch (CombineArchiveException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new CombineArchiveException("failed to read COMBINE archive: " + exception.getMessage(), exception);
        }
        return members;
    }

    private static boolean isDefaultZipEntry(String name) {
        return name.isBlank() || ".".equals(name) || "__MACOSX".equals(name)
                || name.startsWith("__MACOSX/");
    }

    private static boolean isManifestNamespace(String namespace) {
        return MANIFEST_NAMESPACE.equals(namespace) || MANIFEST_NAMESPACE_HTTPS.equals(namespace);
    }

    private static ArrayList<CombineArchiveEntry> manifestEntries(byte[] manifest) {
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
                // A manifest may spell an archive-relative path as "./x"; the zip member is "x".
                // Normalising here is what lets read() bind the entry to its content at all.
                var normalised = location.startsWith("./") ? location.substring(2) : location;
                var candidate = java.nio.file.Path.of(normalised);
                if (candidate.isAbsolute() || candidate.getRoot() != null) {
                    // BioModels BIOMD0000001085 lists /home/... paths (CORP-4). An absolute entry can
                    // never name a zip member, so it is skipped; a "../" escape still fails below.
                    continue;
                }
                requireSafeLocation(normalised);
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

    private static void requireSafeLocation(String location) {
        var normalized = location.replace('\\', '/');
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

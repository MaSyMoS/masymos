package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Documents the variety of real COMBINE archives exported by the JWS Online model repository
 * (JJJ, {@code https://jjj.mib.ac.uk}), without parsing the models inside.
 *
 * <p>The four archives under {@code src/test/resources/jjj-variety} were downloaded on 2026-11-11
 * from the JJJ experiment export endpoint. Note that JJJ experiment slugs are user-specific: the
 * export URL answers 404 unless the slug carries its {@code -user} suffix.
 *
 * <p>Source URL pattern:
 * {@code https://jjj.mib.ac.uk/models/experiments/<slug>/export/combinearchive?download=1}
 *
 * <ul>
 *   <li>{@code adlung2017_fig2g.omex} — 696607 bytes (680 KB), slug {@code adlung2017_fig2g},
 *       source
 *       {@code https://jjj.mib.ac.uk/models/experiments/adlung2017_fig2g/export/combinearchive?download=1}
 *       — multi-model bundle: two SBML models ({@code models/adlung1.sbml},
 *       {@code models/adlung2.sbml}), both declared SBML Level 3 Version 1.</li>
 *   <li>{@code aguda1999_fig5c.omex} — 39343 bytes (38 KB), slug {@code aguda1999_fig5c},
 *       source
 *       {@code https://jjj.mib.ac.uk/models/experiments/aguda1999_fig5c/export/combinearchive?download=1}
 *       — single SBML Level 3 Version 1 model.</li>
 *   <li>{@code zhu2007_fig3-user.omex} — 33957 bytes (33 KB), slug {@code zhu2007_fig3-user},
 *       source
 *       {@code https://jjj.mib.ac.uk/models/experiments/zhu2007_fig3-user/export/combinearchive?download=1}
 *       — single SBML Level 2 Version 4 model.</li>
 *   <li>{@code benson2014_fig5-user.omex} — 317440 bytes (310 KB), slug
 *       {@code benson2014_fig5-user}, source
 *       {@code https://jjj.mib.ac.uk/models/experiments/benson2014_fig5-user/export/combinearchive?download=1}
 *       — single large cardiac SBML Level 2 Version 4 model.</li>
 * </ul>
 *
 * <p>These tests run offline; the fixtures are committed. Only the JDK zip API and string handling
 * are used, so no archive reader and no model parser is exercised here.
 */
class JjjArchiveVarietyTest {

    private static final String RESOURCE_DIR = "src/test/resources/jjj-variety/";

    private static final String SPEC = "http://identifiers.org/combine.specifications/";

    private static final String MANIFEST_NAMESPACE = SPEC + "omex-manifest";

    private static final String SEDML_FORMAT = SPEC + "sed-ml.level-1.version-3";

    private static final Pattern CONTENT = Pattern.compile("<content\\b([^>]*)/?>");

    private static final Pattern ATTRIBUTE = Pattern.compile("\\b(format|location)=\"([^\"]*)\"");

    @Test
    void adlungMultiModelBundleDeclaresTwoSbmlModels() throws IOException {
        var archive = read("adlung2017_fig2g.omex");

        archive.assertWellFormed();
        assertEquals(2, archive.sbmlEntries().size(), "adlung bundle carries two SBML entries");
        assertEquals(Set.of(SPEC + "sbml.level-3.version-1"), archive.formatsByKind("sbml"),
                "both adlung models are SBML Level 3 Version 1");
        assertEquals(1, archive.countOf(SEDML_FORMAT), "exactly one SED-ML entry");
    }

    @Test
    void agudaDeclaresSbmlLevel3Version1() throws IOException {
        var archive = read("aguda1999_fig5c.omex");

        archive.assertWellFormed();
        assertTrue(archive.formats().contains(SPEC + "sbml.level-3.version-1"),
                "aguda must declare SBML Level 3 Version 1, declared: " + archive.formats());
        assertEquals(1, archive.countOf(SEDML_FORMAT), "exactly one SED-ML entry");
    }

    @Test
    void zhuDeclaresSbmlLevel2Version4() throws IOException {
        var archive = read("zhu2007_fig3-user.omex");

        archive.assertWellFormed();
        assertTrue(archive.formats().contains(SPEC + "sbml.level-2.version-4"),
                "zhu must declare SBML Level 2 Version 4, declared: " + archive.formats());
        assertEquals(1, archive.countOf(SEDML_FORMAT), "exactly one SED-ML entry");
    }

    @Test
    void bensonLargeCardiacModelDeclaresSbmlLevel2Version4() throws IOException {
        var archive = read("benson2014_fig5-user.omex");

        archive.assertWellFormed();
        assertTrue(archive.formats().contains(SPEC + "sbml.level-2.version-4"),
                "benson must declare SBML Level 2 Version 4, declared: " + archive.formats());
        assertTrue(archive.sbmlEntries().get(0).getSize() > 100_000,
                "the benson cardiac model is a large model");
        assertEquals(1, archive.countOf(SEDML_FORMAT), "exactly one SED-ML entry");
    }

    @Test
    void everyArchiveUsesTheSameManifestNamespace() throws IOException {
        for (var name : List.of(
                "adlung2017_fig2g.omex",
                "aguda1999_fig5c.omex",
                "zhu2007_fig3-user.omex",
                "benson2014_fig5-user.omex")) {
            var manifest = read(name).manifest();
            assertTrue(manifest.contains("xmlns=\"" + MANIFEST_NAMESPACE + "\""),
                    name + " manifest must use namespace " + MANIFEST_NAMESPACE);
        }
    }

    private record Content(String location, String format) {
    }

    private record Archive(String name, String manifest, List<ZipEntry> entries, List<Content> contents) {

        List<ZipEntry> sbmlEntries() {
            return entries.stream().filter(entry -> entry.getName().endsWith(".sbml")).toList();
        }

        Set<String> formats() {
            var formats = new LinkedHashSet<String>();
            contents.forEach(content -> formats.add(content.format()));
            return formats;
        }

        Set<String> formatsByKind(String kind) {
            var formats = new LinkedHashSet<String>();
            contents.stream()
                    .map(Content::format)
                    .filter(format -> format.startsWith(SPEC + kind + "."))
                    .forEach(formats::add);
            return formats;
        }

        long countOf(String format) {
            return contents.stream().filter(content -> content.format().equals(format)).count();
        }

        void assertWellFormed() {
            var names = entries.stream().map(ZipEntry::getName).toList();
            assertTrue(names.contains("manifest.xml"), name + " must carry a root manifest.xml");
            assertTrue(names.contains("metadata.rdf"), name + " must carry metadata.rdf");
            assertTrue(!sbmlEntries().isEmpty(), name + " must carry at least one .sbml entry");
            assertTrue(sbmlEntries().stream().anyMatch(entry -> entry.getSize() > 0),
                    name + " SBML entries must not be empty");
            assertNotNull(manifest, name + " manifest must be readable");
            assertTrue(!contents.isEmpty(), name + " manifest must declare content entries");
            assertTrue(contents.stream().anyMatch(content -> content.format().startsWith(SPEC + "omex")),
                    name + " manifest must declare the archive itself");
        }
    }

    private static Archive read(String name) throws IOException {
        var path = Path.of(RESOURCE_DIR + name);
        assertTrue(Files.isRegularFile(path), "missing test resource: " + path);
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<ZipEntry> entries = zip.stream().collect(java.util.stream.Collectors.toList());
            var manifestEntry = zip.getEntry("manifest.xml");
            assertNotNull(manifestEntry, name + " must carry a root manifest.xml");

            String manifest;
            try (InputStream stream = zip.getInputStream(manifestEntry)) {
                manifest = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }

            var contents = new ArrayList<Content>();
            Matcher matcher = CONTENT.matcher(manifest);
            while (matcher.find()) {
                String location = null;
                String format = null;
                Matcher attribute = ATTRIBUTE.matcher(matcher.group(1));
                while (attribute.find()) {
                    if ("location".equals(attribute.group(1))) {
                        location = attribute.group(2);
                    } else {
                        format = attribute.group(2);
                    }
                }
                assertNotNull(location, name + " manifest content entry without location: " + matcher.group());
                assertTrue(!location.isBlank(), name + " manifest content entry with blank location");
                assertNotNull(format, name + " manifest content entry without format: " + matcher.group());
                contents.add(new Content(location, format));
            }
            return new Archive(name, manifest, entries, contents);
        }
    }
}

package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * Inspects COMBINE archives and a SED-ML file downloaded unmodified from the JWS Online simulation
 * database (JJJ, {@code https://jjj.mib.ac.uk}), retrieved 2026-11-11. Like {@link PmrRealWorldTest}
 * these fixtures were never touched by us, so they show what the portal actually serves, including the
 * OMEX layout another component must later read.
 *
 * <p>Fixtures, each byte-identical to a fresh download:
 * <ul>
 *   <li>{@code jjj/becker2010_fig2b-user.omex} (36459 bytes): entries {@code manifest.xml},
 *       {@code metadata.rdf}, {@code models/becker1.sbml} (SBML Level 2 Version 4) and
 *       {@code sedml/becker2010_fig2b-user.sedml} (SED-ML level 1 version 3): {@code
 *       https://jjj.mib.ac.uk/models/experiments/becker2010_fig2b-user/export/combinearchive?download=1}</li>
 *   <li>{@code jjj/bhartiya2003_fig3.omex} (37437 bytes): entries {@code manifest.xml},
 *       {@code metadata.rdf}, {@code models/bhartiya1.sbml} (SBML Level 2 Version 4) and
 *       {@code sedml/bhartiya2003_fig3.sedml} (SED-ML level 1 version 3): {@code
 *       https://jjj.mib.ac.uk/models/experiments/bhartiya2003_fig3/export/combinearchive?download=1}</li>
 *   <li>{@code jjj/aguda1999_fig5c.omex} (39343 bytes): entries {@code manifest.xml},
 *       {@code metadata.rdf}, {@code models/aguda1.sbml} (SBML Level 3 Version 1) and
 *       {@code sedml/aguda1999_fig5c.sedml} (SED-ML level 1 version 3): {@code
 *       https://jjj.mib.ac.uk/models/experiments/aguda1999_fig5c/export/combinearchive?download=1}</li>
 *   <li>{@code jjj/bhartiya2003_fig3.sedml} (15656 bytes): SED-ML level 1 version 3 exported on its
 *       own, root {@code <sedML level="1" version="3" xmlns="http://sed-ml.org/sed-ml/level1/version3">},
 *       three model references and three tasks: {@code
 *       https://jjj.mib.ac.uk/models/experiments/bhartiya2003_fig3/export/sedml?download=1}</li>
 * </ul>
 *
 * <p>The three archives cover two SBML language versions (Level 2 Version 4 and Level 3 Version 1),
 * which no curated fixture in this module could claim of files actually published by a portal.
 * These tests only read the archives with the JDK zip API; they do not implement an OMEX reader.
 */
class JjjRealWorldTest {

    private static final String BECKER_OMEX = "jjj/becker2010_fig2b-user.omex";
    private static final String BHARTIYA_OMEX = "jjj/bhartiya2003_fig3.omex";
    private static final String AGUDA_OMEX = "jjj/aguda1999_fig5c.omex";
    private static final String BHARTIYA_SEDML = "jjj/bhartiya2003_fig3.sedml";

    private static final String JJJ_BASE = "https://jjj.mib.ac.uk/models/experiments/";
    private static final String BHARTIYA_SEDML_URL = JJJ_BASE + "bhartiya2003_fig3/export/sedml?download=1";

    @Test
    void beckerArchiveContainsManifestModelAndSedml() throws IOException {
        assertIsCombineArchive(BECKER_OMEX);
    }

    @Test
    void bhartiya2003ArchiveContainsManifestModelAndSedml() throws IOException {
        assertIsCombineArchive(BHARTIYA_OMEX);
    }

    @Test
    void aguda1999ArchiveContainsManifestModelAndSedml() throws IOException {
        assertIsCombineArchive(AGUDA_OMEX);
    }

    @Test
    void parsesJjjSedmlFileWithModelReferencesAndTasks() throws Exception {
        var parsed = parseSedml();

        assertEquals("1", parsed.properties().get("sedml.level"), "JJJ SED-ML must declare level 1");
        assertFalse(parsed.properties().get("sedml.version").isBlank(), "SED-ML version must be recorded");
        var modelReferences = entitiesOfType(parsed, EntityType.SEDML_MODELREFERENCE);
        var tasks = entitiesOfType(parsed, EntityType.SEDML_TASK);
        assertFalse(modelReferences.isEmpty(), "JJJ SED-ML must declare at least one model reference");
        assertFalse(tasks.isEmpty(), "JJJ SED-ML must declare at least one task");
        assertEquals(3, modelReferences.size(), "bhartiya2003_Fig3 declares three model references");
        assertEquals(3, tasks.size(), "bhartiya2003_Fig3 declares three tasks");
        assertTrue(modelReferences.stream().allMatch(entity ->
                        "urn:sedml:language:sbml".equals(entity.properties().get("language"))),
                "every model reference must declare the SBML language URN: " + modelReferences);
    }

    /**
     * A JJJ COMBINE archive must carry a manifest, exactly one SED-ML master file and at least one
     * model in a format the manifest itself names as SBML, CellML or SED-ML.
     */
    private static void assertIsCombineArchive(String fixture) throws IOException {
        Path path = resourcePath(fixture);
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entryNames = zip.stream().map(ZipEntry::getName).toList();
            assertTrue(entryNames.contains("manifest.xml"), fixture + ": missing manifest.xml, got " + entryNames);
            assertTrue(entryNames.stream().anyMatch(name -> name.endsWith(".sedml")),
                    fixture + ": JJJ archive must contain a SED-ML file, got " + entryNames);

            String manifest = readEntry(zip, "manifest.xml");
            assertTrue(manifest.contains(".sbml") || manifest.contains(".cellml") || manifest.contains(".sedml"),
                    fixture + ": manifest must mention a model or simulation description entry");

            var modelEntries = entryNames.stream()
                    .filter(name -> name.endsWith(".sbml") || name.endsWith(".cellml") || name.endsWith(".sedml"))
                    .toList();
            assertFalse(modelEntries.isEmpty(), fixture + ": no model entry in " + entryNames);
            assertTrue(zip.getEntry("metadata.rdf") != null, fixture + ": JJJ archives carry metadata.rdf");
        }
    }

    private static ParsedModel parseSedml() throws Exception {
        var reference = new ModelReference(
                "bhartiya2003_fig3.sedml",
                URI.create(BHARTIYA_SEDML_URL),
                ModelType.SEDML
        );
        try (InputStream stream = open(BHARTIYA_SEDML)) {
            return new SedmlModelParser().parse(new ModelSource(reference), stream);
        }
    }

    private static List<ParsedModelEntity> entitiesOfType(ParsedModel parsed, EntityType type) {
        return parsed.entities().stream().filter(entity -> entity.type() == type).toList();
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        try (InputStream stream = zip.getInputStream(entry)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path resourcePath(String fixture) throws IOException {
        var url = JjjRealWorldTest.class.getClassLoader().getResource(fixture);
        if (url == null) {
            throw new IllegalStateException("missing test resource: " + fixture);
        }
        return Path.of(URI.create(url.toString()));
    }

    private static InputStream open(String fixture) {
        InputStream stream = JjjRealWorldTest.class.getClassLoader().getResourceAsStream(fixture);
        if (stream == null) {
            throw new IllegalStateException("missing test resource: " + fixture);
        }
        return stream;
    }
}

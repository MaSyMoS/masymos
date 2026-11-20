package org.masymos.core.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.masymos.core.archive.CombineArchiveFormat.ArchiveEntryKind;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.UriModelSourceContentLoader;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelType;
import org.masymos.core.spi.ExtractedModelGraphRepository;

class CombineArchiveIndexingServiceTest {
    private static final String SBML_FORMAT =
            "http://identifiers.org/combine.specifications/sbml.level-2.version-4";
    private static final String SEDML_FORMAT =
            "http://identifiers.org/combine.specifications/sed-ml.level-1.version-3";
    private static final String METADATA_FORMAT =
            "http://identifiers.org/combine.specifications/omex-metadata";
    private static final String UNSUPPORTED_FORMAT =
            "http://example.org/formats/plain-text";
    private static final String CELLML_FORMAT =
            "http://identifiers.org/combine.specifications/cellml.2.0";
    /** The committed hand-built archive packages a CellML 1.0 file, so its manifest says 1.0. */
    private static final String CELLML_1_0_FORMAT = "http://identifiers.org/combine.specifications/cellml.1.0";
    private static final String COMMITTED_CELLML_ARCHIVE = "/combine-archives/friel_1995.omex";
    private static final String THIRD_PARTY_CELLML_ARCHIVE = "/combine-archives/vanderpol_cellml.omex";
    /**
     * The SED-ML editors publish this archive with an unversioned format URI, unlike the
     * {@code cellml.2.0} constant above, so the form the real bundle uses is asserted explicitly.
     */
    private static final String UNVERSIONED_CELLML_FORMAT =
            "http://identifiers.org/combine.specifications/cellml";
    private static final String UNVERSIONED_SEDML_FORMAT =
            "http://identifiers.org/combine.specifications/sed-ml";
    private static final String CELLML_BODY = """
            <?xml version='1.0' encoding='UTF-8'?>
            <model xmlns="http://www.cellml.org/cellml/2.0#" name="demo" version="2.0">
              <component name="compartment"/>
            </model>
            """;
    private static final String SEDML_BODY = """
            <?xml version='1.0' encoding='UTF-8'?>
            <sedML xmlns="http://sed-ml.org/sed-ml/level1/version3" level="1" version="3"/>
            """;

    private final List<List<ModelSource>> sbmlCalls = new ArrayList<>();
    private final List<List<ModelSource>> cellmlCalls = new ArrayList<>();
    private final List<List<ModelSource>> sedmlCalls = new ArrayList<>();

    @TempDir
    Path tempDir;

    @Test
    void delegatesOnePerKindAndReportsUnsupportedEntries() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("models/becker1.sbml", "<sbml/>");
        members.put("sedml/becker2010_fig2b-user.sedml", "<sedML/>");
        members.put("metadata.rdf", "<rdf:RDF/>");
        members.put("manifest.xml", manifest(
                content("models/becker1.sbml", SBML_FORMAT),
                content("sedml/becker2010_fig2b-user.sedml", SEDML_FORMAT + "\" master=\"true"),
                content("metadata.rdf", METADATA_FORMAT)));
        var archiveFile = writeArchive("simple.omex", members);

        var result = service().index(archiveFile.toUri());

        assertEquals(2, result.indexedEntries());
        assertEquals(1, sbmlCalls.size());
        assertEquals(1, sedmlCalls.size());
        assertTrue(cellmlCalls.isEmpty(), "no CellML entry, so the CellML service must not be called");

        var sbmlUri = singleUri(sbmlCalls);
        assertEquals("file", sbmlUri.getScheme());
        assertTrue(sbmlUri.getPath().endsWith("/models/becker1.sbml"), "unexpected uri " + sbmlUri);
        assertEquals("file", singleUri(sedmlCalls).getScheme());
        assertTrue(singleUri(sedmlCalls).getPath().endsWith("/sedml/becker2010_fig2b-user.sedml"));

        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().getFirst().startsWith("metadata.rdf:"), result.skipped().toString());
        assertTrue(result.models().isEmpty(), "fake services return no models");
    }

    @Test
    void skipsUnknownKindWithReason() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("notes/readme.txt", "hello");
        members.put("manifest.xml", manifest(content("notes/readme.txt", UNSUPPORTED_FORMAT)));
        var archiveFile = writeArchive("unknown.omex", members);

        var result = service().index(archiveFile.toUri());

        assertEquals(0, result.indexedEntries());
        assertTrue(result.models().isEmpty());
        assertTrue(sbmlCalls.isEmpty());
        assertTrue(cellmlCalls.isEmpty());
        assertTrue(sedmlCalls.isEmpty());
        assertEquals(1, result.skipped().size());
        var reason = result.skipped().getFirst();
        assertTrue(reason.startsWith("notes/readme.txt:"), reason);
        assertTrue(reason.contains("OTHER"), reason);
    }

    @Test
    void usesStableArchiveUrisInsteadOfTemporaryFiles() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("models/becker1.sbml", "<sbml/>");
        members.put("manifest.xml", manifest(content("models/becker1.sbml", SBML_FORMAT)));
        var archiveFile = writeArchive("stable.omex", members);

        service().index(archiveFile.toUri());

        var entryUri = singleUri(sbmlCalls);
        assertEquals(archiveFile.toUri() + "!/models/becker1.sbml", entryUri.toString());
        assertFalse(entryUri.toString().contains(System.getProperty("java.io.tmpdir")),
                "entry URIs must not point into the temp directory: " + entryUri);
    }

    @Test
    void reportsIndexingFailureWithoutTouchingTheFilesystem() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("models/becker1.sbml", "<sbml/>");
        members.put("manifest.xml", manifest(content("models/becker1.sbml", SBML_FORMAT)));
        var archiveFile = writeArchive("failing.omex", members);
        var recorded = new ArrayList<URI>();
        var failing = new CombineArchiveIndexingService(
                sources -> {
                    sources.forEach(source -> recorded.add(source.reference().sourceUri()));
                    throw new IOException("parser exploded");
                },
                sources -> List.of(),
                sources -> List.of(),
                new CombineArchiveReader());

        assertThrows(CombineArchiveException.class, () -> failing.index(archiveFile.toUri()));

        assertEquals(1, recorded.size());
        assertTrue(archiveFile.toUri().toString().startsWith(recorded.getFirst().toString().split("!/")[0]),
                "the failing entry must still be addressed by its archive URI: " + recorded.getFirst());
    }

    @Test
    void delegatesCellmlEntriesToTheCellmlIndexer() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("models/demo.cellml", CELLML_BODY);
        members.put("sedml/demo.sedml", SEDML_BODY);
        members.put("metadata.rdf", "<rdf:RDF/>");
        members.put("manifest.xml", manifest(
                content("models/demo.cellml", CELLML_FORMAT),
                content("sedml/demo.sedml", SEDML_FORMAT),
                content("metadata.rdf", METADATA_FORMAT)));
        var archiveFile = writeArchive("cellml.omex", members);

        var result = service().index(archiveFile.toUri());

        assertEquals(2, result.indexedEntries());
        assertTrue(sbmlCalls.isEmpty(), "no SBML entry, so the SBML service must not be called");
        assertEquals(1, cellmlCalls.size());
        assertEquals(1, sedmlCalls.size());

        var cellmlUri = singleUri(cellmlCalls);
        assertEquals("file", cellmlUri.getScheme());
        assertTrue(cellmlUri.getPath().endsWith("/models/demo.cellml"), "unexpected uri " + cellmlUri);
        var sedmlUri = singleUri(sedmlCalls);
        assertEquals("file", sedmlUri.getScheme());
        assertTrue(sedmlUri.getPath().endsWith("/sedml/demo.sedml"), "unexpected uri " + sedmlUri);
        assertEquals(1, result.skipped().size());
    }

    @Test
    void indexesMultiModelArchiveWithOneCallPerKind() throws IOException {
        var members = new LinkedHashMap<String, String>();
        members.put("models/a.sbml", "<sbml/>");
        members.put("models/b.sbml", "<sbml/>");
        members.put("sedml/demo.sedml", SEDML_BODY);
        members.put("manifest.xml", manifest(
                content("models/a.sbml", SBML_FORMAT),
                content("models/b.sbml", SBML_FORMAT),
                content("sedml/demo.sedml", SEDML_FORMAT)));
        var archiveFile = writeArchive("multi.omex", members);

        var result = service().index(archiveFile.toUri());

        assertEquals(3, result.indexedEntries());
        assertEquals(1, sbmlCalls.size(), "one call per kind, not one per entry");
        assertEquals(2, sbmlCalls.getFirst().size());
        assertTrue(cellmlCalls.isEmpty());
        assertEquals(1, sedmlCalls.size());
        assertEquals(1, sedmlCalls.getFirst().size());

        var sbmlPaths = sbmlCalls.getFirst().stream()
                .map(source -> source.reference().sourceUri().getPath())
                .toList();
        assertTrue(sbmlPaths.stream().anyMatch(path -> path.endsWith("/models/a.sbml")), sbmlPaths.toString());
        assertTrue(sbmlPaths.stream().anyMatch(path -> path.endsWith("/models/b.sbml")), sbmlPaths.toString());
        assertTrue(singleUri(sedmlCalls).getPath().endsWith("/sedml/demo.sedml"));
    }

    @Test
    void everyIndexedEntryCarriesTheDigestOfItsBytesAsVersionLabel() throws IOException {
        var sbmlBody = "<sbml level=\"2\" version=\"4\"/>";
        var archiveFile = writeArchive("labelled.omex", archiveWith("models/becker1.sbml", sbmlBody));

        service().index(archiveFile.toUri());

        var reference = sbmlCalls.getFirst().getFirst().reference();
        assertEquals(ContentDigest.versionLabel(sbmlBody.getBytes(StandardCharsets.UTF_8)), reference.versionId());
        assertEquals("becker1.sbml", reference.fileId());
        assertTrue(reference.versionId().startsWith("sha256-"), reference.versionId());
    }

    @Test
    void twoArchiveRevisionsOfTheSameEntryGetDifferentVersionLabels() throws IOException {
        var first = writeArchive("release-1.omex", archiveWith("models/becker1.sbml", "<sbml level=\"2\" version=\"4\"/>"));
        var second = writeArchive("release-2.omex", archiveWith("models/becker1.sbml", "<sbml level=\"2\" version=\"5\"/>"));
        var unchanged =
                writeArchive("release-3.omex", archiveWith("models/becker1.sbml", "<sbml level=\"2\" version=\"4\"/>"));

        service().index(first.toUri());
        service().index(second.toUri());
        service().index(unchanged.toUri());

        var labels = sbmlCalls.stream()
                .map(call -> call.getFirst().reference().versionId())
                .toList();
        assertEquals(3, labels.size());
        assertNotEquals(labels.get(0), labels.get(1), "a changed entry must get a new version label");
        assertEquals(labels.get(0), labels.get(2), "identical content keeps its label");
    }

    /**
     * DATA-2 built this archive before a third party's bundle was found: it is read from
     * {@code src/test/resources}, hand-built outside this repo's generator from the PMR CellML file
     * {@code friel_1995.cellml}, and indexed with the real CellML service. It stays as the second
     * CellML archive; {@link #indexesThirdPartyArchiveProvingCellmlAndItsSedmlSibling} covers the
     * third-party bundle that actually closed the task.
     */
    @Test
    void indexesCommittedHandBuiltArchiveAndRecognisesItsCellmlEntry() throws IOException {
        var archiveFile = committedPath(COMMITTED_CELLML_ARCHIVE);
        var archive = new CombineArchiveReader().read(Files.newInputStream(archiveFile));
        var entries = archive.entries();

        assertEquals(List.of("manifest.xml", "metadata.rdf", "models/friel_1995.cellml"),
                entries.stream().map(CombineArchiveEntry::location).toList(),
                "the root \".\" omex entry is not a content entry");
        var cellmlEntry = entries.get(2);
        assertEquals(ArchiveEntryKind.CELLML, cellmlEntry.format().kind());
        assertEquals(CELLML_1_0_FORMAT, cellmlEntry.format().formatUri());
        assertEquals(ArchiveEntryKind.METADATA, entries.get(1).format().kind());
        assertTrue(cellmlEntry.master(), "models/friel_1995.cellml must be the master entry");

        var repository = new RecordingGraphRepository();
        var indexingService = new CombineArchiveIndexingService(
                sources -> {
                    throw new AssertionError("no SBML entry, so the SBML service must not be called");
                },
                sources -> new CellmlModelIndexingService(
                        new ModelSourceDiscovery(),
                        new UriModelSourceContentLoader(),
                        new CellmlModelParser(),
                        new ParsedModelGraphMapper(),
                        repository)
                        .indexSources(sources),
                sources -> {
                    throw new AssertionError("no SEDML entry, so the SEDML service must not be called");
                },
                new CombineArchiveReader(),
                repository);

        var result = indexingService.index(archiveFile.toUri());

        assertEquals(1, result.indexedEntries());
        assertEquals(List.of(
                        "manifest.xml: unsupported entry kind OTHER",
                        "metadata.rdf: unsupported entry kind METADATA"),
                result.skipped());
        assertEquals(1, result.models().size());
        var indexed = result.models().getFirst();
        assertEquals(ModelType.CELLML, indexed.source().reference().modelType());
        assertEquals("friel_1995.cellml", indexed.identifier().fileId());
        assertTrue(indexed.source().reference().sourceUri().toString()
                .endsWith("friel_1995.omex!/models/friel_1995.cellml"));
        assertEquals(ContentDigest.versionLabel(archive.content("models/friel_1995.cellml").orElseThrow()),
                indexed.source().reference().versionId());

        assertEquals(1, repository.graphs.size());
        var graph = repository.graphs.getFirst();
        var modelNode = graph.nodes().stream()
                .filter(node -> node.labels().contains("Model"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no model node written: " + graph.nodes()));
        assertEquals("friel_1995.cellml", modelNode.properties().get("fileId"));
        assertEquals(Set.of("Model", "CELLML_MODEL"), modelNode.labels());
        assertEquals("friel_1995", modelNode.properties().get("modelId"));
        assertEquals("friel_1995", modelNode.properties().get("name"));

        var components = graph.nodes().stream()
                .filter(node -> node.labels().contains("CELLML_COMPONENT"))
                .toList();
        assertEquals(List.of("environment", "rate_constants", "cytosolic_calcium", "subspace_calcium"),
                components.stream().map(node -> node.properties().get("name")).toList());
        var calciumVariables = graph.nodes().stream()
                .filter(node -> node.labels().contains("CELLML_VARIABLE"))
                .filter(node -> "Ca_i".equals(node.properties().get("name")))
                .toList();
        assertEquals(3, calciumVariables.size(),
                "Ca_i is declared in rate_constants, cytosolic_calcium and subspace_calcium");
        assertEquals(Set.of("rate_constants", "cytosolic_calcium", "subspace_calcium"),
                calciumVariables.stream().map(node -> node.properties().get("component")).collect(Collectors.toSet()),
                "each declaration belongs to its own component");
        assertTrue(calciumVariables.stream().allMatch(node -> "nanomolar".equals(node.properties().get("units"))),
                calciumVariables.toString());
        assertEquals(1, repository.archiveLinks.size(), "the archive must be linked to its one indexed entry");
    }

    /**
     * DATA-2, closed 2026-11-16: this fixture is a third party's bundle, not one built here.
     * {@code vanderpol_cellml.omex} is the van der Pol example from the SED-ML editors' Level 1
     * Version 5 example set ({@code SED-ML/sed-ml},
     * {@code specification/level-1-version-5/examples/__omex__/L1V5_vanderpol-cellml.omex}),
     * committed unmodified (sha256 {@code D6F24B5A…91FEFD}, identical on a second download).
     *
     * <p>Two facts of the real bundle differ from the hand-built one and are asserted here: the
     * CellML format URI carries no version segment, and the manifest marks the SED-ML document, not
     * the CellML model, as the master entry.</p>
     */
    @Test
    void indexesThirdPartyArchiveProvingCellmlAndItsSedmlSibling() throws IOException {
        var archiveFile = committedPath(THIRD_PARTY_CELLML_ARCHIVE);
        var archive = new CombineArchiveReader().read(Files.newInputStream(archiveFile));

        var entries = archive.entries();
        assertEquals(List.of(
                        "manifest.xml=OTHER",
                        "metadata.rdf=METADATA",
                        "manifest.json=OTHER",
                        "vanderpol.xml=SEDML",
                        "vanderpol.rst=OTHER",
                        "vanderpol-model.cellml=CELLML",
                        "results/opencor/plot1.pdf=OTHER",
                        "results/opencor/plot2.pdf=OTHER",
                        "results/sedml_webtools/plot1.pdf=OTHER",
                        "results/sedml_webtools/plot2.pdf=OTHER"),
                entries.stream()
                        .map(entry -> entry.location() + "=" + entry.format().kind())
                        .toList(),
                "the manifest declares ten content entries beside the root \".\" one");

        var cellmlEntry = entry(entries, "vanderpol-model.cellml");
        assertEquals(UNVERSIONED_CELLML_FORMAT, cellmlEntry.format().formatUri());
        assertNull(cellmlEntry.format().level(), "the unversioned URI carries no level segment");
        assertNull(cellmlEntry.format().version(), "the unversioned URI carries no version segment");
        assertFalse(cellmlEntry.master(), "the manifest marks the SED-ML document as master, not the model");
        var sedmlEntry = entry(entries, "vanderpol.xml");
        assertEquals(UNVERSIONED_SEDML_FORMAT, sedmlEntry.format().formatUri());
        assertTrue(sedmlEntry.master(), "vanderpol.xml must be the master entry");

        // The pair is real, not a model dropped into an unrelated archive: the SED-ML document names
        // the CellML entry as its model source and declares the CellML 1.0 language.
        var sedml = new String(archive.content("vanderpol.xml").orElseThrow(), StandardCharsets.UTF_8);
        assertTrue(sedml.contains("source=\"vanderpol-model.cellml\""),
                "the SED-ML document must reference the CellML entry");
        assertTrue(sedml.contains("urn:sedml:language:cellml.1_0"),
                "the SED-ML document must declare CellML 1.0");

        var repository = new RecordingGraphRepository();
        var recordedSedml = new ArrayList<List<ModelSource>>();
        var indexingService = new CombineArchiveIndexingService(
                sources -> {
                    throw new AssertionError("no SBML entry, so the SBML service must not be called");
                },
                sources -> new CellmlModelIndexingService(
                        new ModelSourceDiscovery(),
                        new UriModelSourceContentLoader(),
                        new CellmlModelParser(),
                        new ParsedModelGraphMapper(),
                        repository)
                        .indexSources(sources),
                sources -> {
                    recordedSedml.add(List.copyOf(sources));
                    return List.of();
                },
                new CombineArchiveReader(),
                repository);

        var result = indexingService.index(archiveFile.toUri());

        assertEquals(2, result.indexedEntries(), "only the CellML and SED-ML entries are indexable kinds");
        assertEquals(List.of(
                        "manifest.xml: unsupported entry kind OTHER",
                        "metadata.rdf: unsupported entry kind METADATA",
                        "manifest.json: unsupported entry kind OTHER",
                        "vanderpol.rst: unsupported entry kind OTHER",
                        "results/opencor/plot1.pdf: unsupported entry kind OTHER",
                        "results/opencor/plot2.pdf: unsupported entry kind OTHER",
                        "results/sedml_webtools/plot1.pdf: unsupported entry kind OTHER",
                        "results/sedml_webtools/plot2.pdf: unsupported entry kind OTHER"),
                result.skipped());
        assertEquals(1, recordedSedml.size(), "one call per kind, carrying the one SED-ML entry");
        assertEquals("vanderpol.xml", recordedSedml.getFirst().getFirst().reference().fileId());

        assertEquals(1, result.models().size());
        var indexed = result.models().getFirst();
        assertEquals(ModelType.CELLML, indexed.source().reference().modelType());
        assertEquals("vanderpol-model.cellml", indexed.identifier().fileId());
        assertTrue(indexed.source().reference().sourceUri().toString()
                .endsWith("vanderpol_cellml.omex!/vanderpol-model.cellml"));
        assertEquals(ContentDigest.versionLabel(archive.content("vanderpol-model.cellml").orElseThrow()),
                indexed.source().reference().versionId());

        assertEquals(1, repository.graphs.size());
        var graph = repository.graphs.getFirst();
        var modelNode = graph.nodes().stream()
                .filter(node -> node.labels().contains("Model"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no model node written: " + graph.nodes()));
        assertEquals("vanderpol-model.cellml", modelNode.properties().get("fileId"));
        assertEquals(Set.of("Model", "CELLML_MODEL"), modelNode.labels());
        assertEquals("van_der_pol_model", modelNode.properties().get("modelId"));
        assertEquals("van_der_pol_model", modelNode.properties().get("name"));
        assertEquals("1.0", modelNode.properties().get("cellmlVersion"),
                "the version comes from the namespace, since the file declares none");

        var documentNode = graph.nodes().stream()
                .filter(node -> node.labels().contains("Document"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no document node written: " + graph.nodes()));
        assertEquals("1", documentNode.properties().get("cellml.components"));
        assertEquals("4", documentNode.properties().get("cellml.variables"));

        var componentNames = graph.nodes().stream()
                .filter(node -> node.labels().contains("CELLML_COMPONENT"))
                .map(node -> node.properties().get("name"))
                .toList();
        assertEquals(List.of("main"), componentNames);
        var variables = graph.nodes().stream()
                .filter(node -> node.labels().contains("CELLML_VARIABLE"))
                .toList();
        assertEquals(List.of("t", "x", "y", "mu"),
                variables.stream().map(node -> node.properties().get("name")).toList());
        assertTrue(variables.stream().allMatch(node -> "main".equals(node.properties().get("component"))),
                variables.toString());
        assertTrue(variables.stream().allMatch(node -> "dimensionless".equals(node.properties().get("units"))),
                variables.toString());
        assertEquals(1, repository.archiveLinks.size(), "the archive must be linked to both indexed entries");
    }

    private static CombineArchiveEntry entry(List<CombineArchiveEntry> entries, String location) {
        return entries.stream()
                .filter(candidate -> location.equals(candidate.location()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing entry " + location + " in " + entries));
    }

    /**
     * The archive is read from the classpath, i.e. from the committed test resource; nothing is
     * generated, and the test fails naming the path if the fixture is missing.
     */
    private static Path committedPath(String resource) throws IOException {
        try {
            var url = CombineArchiveIndexingServiceTest.class.getResource(resource);
            assertTrue(url != null, "missing committed fixture " + resource);
            return Path.of(url.toURI());
        } catch (URISyntaxException exception) {
            throw new IOException("unusable fixture URL " + resource, exception);
        }
    }

    private static final class RecordingGraphRepository implements ExtractedModelGraphRepository {
        private final ArrayList<ExtractedModelGraph> graphs = new ArrayList<>();
        private final ArrayList<URI> archiveLinks = new ArrayList<>();

        @Override
        public ModelIdentifier insert(ExtractedModelGraph graph) {
            graphs.add(graph);
            return new ModelIdentifier("uid-" + graphs.size(), graph.reference().fileId());
        }

        @Override
        public void linkArchive(URI archiveUri, List<URI> entrySourceUris) {
            archiveLinks.add(archiveUri);
        }
    }

    private CombineArchiveIndexingService service() {
        return new CombineArchiveIndexingService(
                sources -> {
                    sbmlCalls.add(List.copyOf(sources));
                    return List.of();
                },
                sources -> {
                    cellmlCalls.add(List.copyOf(sources));
                    return List.of();
                },
                sources -> {
                    sedmlCalls.add(List.copyOf(sources));
                    return List.of();
                },
                new CombineArchiveReader());
    }

    private static Map<String, String> archiveWith(String location, String body) {
        var members = new LinkedHashMap<String, String>();
        members.put(location, body);
        members.put("manifest.xml", manifest(content(location, SBML_FORMAT)));
        return members;
    }

    private static URI singleUri(List<List<ModelSource>> calls) {
        assertEquals(1, calls.size());
        assertEquals(1, calls.getFirst().size());
        return calls.getFirst().getFirst().reference().sourceUri();
    }

    private static String manifest(String... contentElements) {
        var builder = new StringBuilder()
                .append("<?xml version='1.0' encoding='utf-8'?>\n")
                .append("<omexManifest xmlns=\"http://identifiers.org/combine.specifications/omex-manifest\">\n")
                .append("  <content location=\".\" format=\"http://identifiers.org/combine.specifications/omex\" />\n");
        for (var element : contentElements) {
            builder.append("  ").append(element).append('\n');
        }
        return builder.append("</omexManifest>\n").toString();
    }

    private static String content(String location, String format) {
        return "<content location=\"" + location + "\" format=\"" + format + "\" />";
    }

    private Path writeArchive(String name, Map<String, String> members) throws IOException {
        var target = tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(target); var zip = new ZipOutputStream(out)) {
            for (var member : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return target;
    }
}

package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.model.HostConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.masymos.core.archive.CombineArchiveReader;
import org.masymos.core.corpus.CorpusManifest;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Replays the harvested corpus into a real store and measures what it covered (CORP-4).
 *
 * <p>Opt-in twice over: the class name ends in {@code ContainersIT}, so the external-Neo4j lane -
 * which matches every {@code IT.java} and excludes the {@code ContainersIT.java} family - never runs
 * it against a maintainer's own database, and it skips with a stated assumption unless
 * {@code -Dmasymos.corpus.manifest} names a manifest. With a cache present it runs inside
 * {@code -Pcorpus} and inside {@code -Ptestcontainers-it}.
 *
 * <p>It proves parse + graph write + the unit layer. The SED-ML linking pass and annotation
 * enrichment are not replayed: the first is a maintenance step over an already written graph, the
 * second calls term providers and tests get no network.
 */
@Testcontainers(disabledWithoutDocker = true)
class CorpusCoverageContainersIT {

    @Container
    static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>(DockerImageName.parse("neo4j:2026.05"))
            .withoutAuthentication()
            // Docker 19.03 (LinuxKit 4.19) ships a seccomp profile without clone3, which glibc >= 2.34
            // uses to create threads; JVM containers otherwise die with pthread_create (EPERM).
            .withCreateContainerCmdModifier(cmd -> {
                var hostConfig = cmd.getHostConfig() == null ? HostConfig.newHostConfig() : cmd.getHostConfig();
                cmd.withHostConfig(hostConfig.withSecurityOpts(List.of("seccomp=unconfined")));
            });

    @Test
    void replaysTheHarvestedCorpusAndMeasuresCoverage() throws IOException {
        var manifestProperty = System.getProperty("masymos.corpus.manifest");
        assumeTrue(manifestProperty != null, "no corpus manifest configured (-Dmasymos.corpus.manifest)");

        var manifestPath = Path.of(manifestProperty).toAbsolutePath().normalize();
        assumeTrue(Files.exists(manifestPath), "manifest not harvested yet: " + manifestPath);
        var corpusRoot = manifestPath.getParent();
        var manifest = CorpusManifest.load(manifestPath, corpusRoot.resolve("skip.tsv"));
        var notYetSeen = readNotYetSeen(corpusRoot.resolve("not-yet-seen.tsv"));

        var hashProblems = verifyHashes(manifest, corpusRoot);
        assertTrue(hashProblems.isEmpty(), "the cache does not match the manifest:\n" + String.join("\n", hashProblems));

        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var outcomes = new LinkedHashMap<String, String>();
        var strata = new TreeMap<String, Long>();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            for (var row : manifest.rows()) {
                strata.merge(row.format() + " " + row.level() + " " + row.version() + " " + row.curation() + " " + row.source(), 1L, Long::sum);
                try {
                    var parsed = parseRow(row, corpusRoot);
                    if (parsed.isEmpty()) {
                        // A curated archive whose members all lost their content is the exact bug
                        // that once recorded "ok" over an empty document (CORP-4 review).
                        outcomes.put(row.sha256(), "no document: the archive declared no parsable member with content");
                        continue;
                    }
                    var mismatch = versionMismatch(row, parsed);
                    if (mismatch != null) {
                        outcomes.put(row.sha256(), mismatch);
                        continue;
                    }
                    for (var model : parsed) {
                        repository.insert(mapper.toGraph(model));
                    }
                    outcomes.put(row.sha256(), "ok");
                } catch (RuntimeException | IOException failure) {
                    var frame = failure.getStackTrace().length > 0 ? failure.getStackTrace()[0].toString() : "?";
                    // The first frame is what turns "something threw" into a located defect.
                    outcomes.put(row.sha256(), failure.getClass().getSimpleName() + ": " + failure.getMessage()
                            + " @ " + frame);
                }
            }

            var labels = counts(session, "MATCH (n) UNWIND labels(n) AS l RETURN l AS key, count(*) AS n");
            var relationships = counts(session, "MATCH ()-[r]->() RETURN type(r) AS key, count(*) AS n");
            var versions = new TreeMap<String, Long>();
            manifest.rows().stream()
                    .filter(row -> "ok".equals(outcomes.get(row.sha256())))
                    .forEach(row -> versions.merge(
                            row.format() + " " + row.level() + " " + row.version(), 1L, Long::sum));

            var report = CorpusCoverage.report(labels, relationships, versions, strata, notYetSeen);
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target", "corpus-report.md"),
                    "# Corpus coverage (CORP-4)\n\n" + report);

            assertCuratedRowsAllIngested(manifest, outcomes);
            assertNonCuratedFailuresAreSkipped(manifest, outcomes);
            assertSkipsStillReproduce(manifest, outcomes);
            assertEquals(List.of(), CorpusCoverage.gaps(labels, relationships, versions, notYetSeen),
                    "the report names every uncovered construct; this list must be empty:\n" + report);
            assertEquals(List.of(), staleExplanations(notYetSeen, labels, relationships, versions),
                    "a not-yet-seen row for a construct the sample now covers would hide a future regression");
        }
    }

    private static void assertCuratedRowsAllIngested(CorpusManifest manifest, Map<String, String> outcomes) {
        var failures = manifest.rows().stream()
                .filter(row -> row.curation() == CorpusManifest.Curation.CURATED)
                .filter(row -> !"ok".equals(outcomes.get(row.sha256())))
                .map(row -> row.file() + " -> " + outcomes.get(row.sha256()))
                .toList();
        assertTrue(failures.isEmpty(), "a curated sample must ingest; no skip is legal for it:\n" + String.join("\n", failures));
    }

    private static void assertNonCuratedFailuresAreSkipped(CorpusManifest manifest, Map<String, String> outcomes) {
        var skipped = manifest.skips().stream().map(CorpusManifest.Skip::sha256).collect(java.util.stream.Collectors.toSet());
        var unexplained = manifest.rows().stream()
                .filter(row -> row.curation() == CorpusManifest.Curation.NON_CURATED)
                .filter(row -> !"ok".equals(outcomes.get(row.sha256())))
                .filter(row -> !skipped.contains(row.sha256()))
                .map(row -> row.file() + " -> " + outcomes.get(row.sha256()))
                .toList();
        assertTrue(unexplained.isEmpty(),
                "a non-curated failure must be recorded in skip.tsv with a reason:\n" + String.join("\n", unexplained));
    }

    private static void assertSkipsStillReproduce(CorpusManifest manifest, Map<String, String> outcomes) {
        var stale = manifest.skips().stream()
                .filter(skip -> "ok".equals(outcomes.get(skip.sha256())))
                .map(CorpusManifest.Skip::sha256)
                .toList();
        assertTrue(stale.isEmpty(), "these skips no longer reproduce, so remove them: " + stale);
    }

    private static List<ParsedModel> parseRow(CorpusManifest.Row row, Path corpusRoot) throws IOException {
        var file = corpusRoot.resolve("files").resolve(row.file());
        var bytes = Files.readAllBytes(file);
        var parsed = new ArrayList<ParsedModel>();
        switch (row.format()) {
            case "SBML" -> parsed.add(new SbmlModelParser()
                    .parse(source(row, ModelType.SBML), new ByteArrayInputStream(bytes)));
            case "CELLML" -> parsed.add(new CellmlModelParser()
                    .parse(source(row, ModelType.CELLML), new ByteArrayInputStream(bytes)));
            case "OMEX" -> {
                var archive = new CombineArchiveReader().read(new ByteArrayInputStream(bytes));
                for (var entry : archive.entries()) {
                    var content = archive.content(entry.location());
                    if (content.isEmpty()) {
                        continue;
                    }
                    var type = switch (entry.format().kind()) {
                        case SBML -> ModelType.SBML;
                        case CELLML -> ModelType.CELLML;
                        case SEDML -> ModelType.SEDML;
                        default -> null;
                    };
                    if (type == null) {
                        continue;
                    }
                    var reference = new ModelReference(row.id() + "!" + entry.location(),
                            URI.create("urn:masymos:corpus:" + row.id() + "/" + entry.location()), type);
                    var modelSource = new ModelSource(reference);
                    parsed.add(switch (type) {
                        case SBML -> new SbmlModelParser().parse(modelSource, new ByteArrayInputStream(content.get()));
                        case CELLML -> new CellmlModelParser().parse(modelSource, new ByteArrayInputStream(content.get()));
                        default -> new org.masymos.core.extract.SedmlModelParser()
                                .parse(modelSource, new ByteArrayInputStream(content.get()));
                    });
                }
            }
            default -> throw new IllegalArgumentException("unsupported corpus format " + row.format());
        }
        return parsed;
    }

    private static ModelSource source(CorpusManifest.Row row, ModelType type) {
        return new ModelSource(new ModelReference(row.id(), URI.create("urn:masymos:corpus:" + row.id()), type));
    }

    private static Map<String, Long> counts(org.neo4j.driver.Session session, String cypher) {
        var result = new LinkedHashMap<String, Long>();
        session.executeRead(tx -> tx.run(cypher).list(record -> Map.entry(record.get("key").asString(), record.get("n").asLong())))
                .forEach(entry -> result.merge(entry.getKey(), entry.getValue(), Long::sum));
        return result;
    }

    private static List<String> verifyHashes(CorpusManifest manifest, Path corpusRoot) {
        var problems = new ArrayList<String>();
        for (var row : manifest.rows()) {
            var file = corpusRoot.resolve("files").resolve(row.file());
            if (!Files.exists(file)) {
                problems.add("missing cached file: " + row.file());
                continue;
            }
            try {
                var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                if (!actual.equals(row.sha256())) {
                    problems.add("sha256 mismatch for " + row.file() + ": manifest " + row.sha256() + ", file " + actual);
                }
            } catch (IOException | NoSuchAlgorithmException failure) {
                problems.add("cannot hash " + row.file() + ": " + failure.getMessage());
            }
        }
        return problems;
    }

    /**
     * The {@code not-yet-seen} rows that now describe a covered construct. Symmetric to the stale-skip
     * rule: an explanation that outlives its gap would silence the next regression.
     */
    private static List<String> staleExplanations(
            Set<String> notYetSeen, Map<String, Long> labels, Map<String, Long> relationships,
            Map<String, Long> versions) {
        var stale = new ArrayList<String>();
        for (var entry : notYetSeen) {
            var split = entry.indexOf(' ');
            if (split < 0) {
                continue;
            }
            var vocabulary = entry.substring(0, split);
            var key = entry.substring(split + 1);
            var covered = switch (vocabulary) {
                case CorpusCoverage.LABEL -> labels.getOrDefault(key, 0L) > 0;
                case CorpusCoverage.RELATIONSHIP -> relationships.getOrDefault(key, 0L) > 0;
                case CorpusCoverage.VERSION -> versions.getOrDefault(key, 0L) > 0;
                default -> false;
            };
            if (covered) {
                stale.add(entry);
            }
        }
        return stale;
    }

    /**
     * What the parsed document says about itself versus what the manifest claims. The manifest states
     * the source's claim; only the bytes are the truth, so a wrong listing cannot count as covered.
     */
    private static String versionMismatch(CorpusManifest.Row row, List<ParsedModel> parsed) {
        for (var model : parsed) {
            var sbmlLevel = model.properties().get("sbml.level");
            var sbmlVersion = model.properties().get("sbml.version");
            if ("SBML".equals(row.format()) && sbmlLevel != null && sbmlVersion != null
                    && !(row.level().equals(sbmlLevel) && row.version().equals(sbmlVersion))) {
                return "manifest says SBML " + row.level() + "." + row.version()
                        + " but the file says " + sbmlLevel + "." + sbmlVersion;
            }
            var cellmlVersion = model.properties().get("cellml.version");
            if ("CELLML".equals(row.format()) && cellmlVersion != null && !row.version().equals(cellmlVersion)) {
                return "manifest says CellML " + row.version() + " but the file says " + cellmlVersion;
            }
        }
        return null;
    }

    private static Set<String> readNotYetSeen(Path path) throws IOException {
        if (Files.notExists(path)) {
            return Set.of();
        }
        var lines = Files.readAllLines(path);
        var keys = new LinkedHashSet<String>();
        for (var index = 1; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            var fields = line.split("\t", -1);
            if (fields.length < 3 || fields[2].isBlank()) {
                throw new IllegalArgumentException("not-yet-seen.tsv:" + (index + 1) + ": every row needs a reason");
            }
            keys.add(fields[0].trim() + " " + fields[1].trim());
        }
        return keys;
    }
}

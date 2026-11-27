package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.model.HostConfig;
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
import org.masymos.core.corpus.CorpusManifest;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.ParsedModelGraphMapper;
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
 * {@code -Dmasymos.corpus.manifest} names a manifest. Once that property is present the manifest is
 * no longer optional: an absent file fails the test, because a configured lane that skips green
 * would disable the guard for every corpus claim. With a cache present it runs inside
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
            // The project's documented profile (developer-setup.md §8): without memory environment a
            // neo4j:2026.05 container leaves the heap unset and transaction memory at 344.40 MiB, which
            // cannot write the graph of the 20.8 MB MODEL2203300002 (measured 2026-11-25, third run of
            // CORP-17). These two settings make the container report exactly the documented
            // heap 1.00 GiB / pagecache 512.00 MiB / dbms.memory.transaction.total.max 716.80 MiB.
            .withEnv("NEO4J_server_memory_heap_max__size", "1G")
            .withEnv("NEO4J_server_memory_pagecache_size", "512m")
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
        if (Files.notExists(manifestPath)) {
            fail("the corpus lane is configured but its manifest is absent: " + manifestPath
                    + " - a lane that reports green without having run the corpus would disable the guard"
                    + " for every corpus claim");
        }
        var corpusRoot = manifestPath.getParent();
        var manifest = CorpusManifest.load(manifestPath, corpusRoot.resolve("skip.tsv"));
        var notYetSeen = readNotYetSeen(corpusRoot.resolve("not-yet-seen.tsv"));

        var hashProblems = verifyHashes(manifest, corpusRoot);
        assertTrue(hashProblems.isEmpty(), "the cache does not match the manifest:\n" + String.join("\n", hashProblems));

        var settings = new Neo4jSettings(URI.create(NEO4J.getBoltUrl()), "neo4j", "", "neo4j");
        var sessionConfig = SessionConfig.builder().withDatabase(settings.database()).build();
        var outcomes = new LinkedHashMap<String, String>();
        var strata = new TreeMap<String, Long>();
        // CORP-9: samples JSBML could only read after dropping unparsable namespaces, by file id.
        var reducedReads = new TreeMap<String, String>();
        // CORP-10: the documents the shipped CellML resolver enriched from a harvested @@cmeta sidecar.
        var externalCmeta = new LinkedHashSet<String>();
        // CORP-12: the version dimension counts what each ingested document says of itself, so an
        // archive contributes one entry per member instead of `OMEX - -`.
        var countedDocuments = new ArrayList<ParsedModel>();
        // CORP-16: what the storage path keeps of each document, so the report can say which stored copy
        // is shorter than the original instead of leaving its own fidelity unstated.
        var storedDocuments = new ArrayList<CorpusCoverage.StoredCopy>();

        try (var driver = Neo4jDriverFactory.create(settings);
                var session = driver.session(sessionConfig)) {
            new Neo4jSchemaInitializer(driver, settings.database()).initialize();
            var repository = new Neo4jExtractedModelGraphRepository(driver, settings.database());
            var mapper = new ParsedModelGraphMapper();

            for (var row : manifest.rows()) {
                if (row.role() == CorpusManifest.Role.SIDECAR) {
                    // A sidecar is a cache file its model reads, not a document: counting it would count
                    // one model twice in ## Sample (CORP-10). assertEverySidecarReachedItsModel proves
                    // each of them was actually read instead of silently skipped.
                    continue;
                }
                strata.merge(row.format() + " " + row.level() + " " + row.version() + " " + row.curation() + " " + row.source(), 1L, Long::sum);
                try {
                    var read = CorpusDocuments.of(row, corpusRoot);
                    if (read.isEmpty()) {
                        // A curated archive whose members all lost their content is the exact bug
                        // that once recorded "ok" over an empty document (CORP-4 review).
                        outcomes.put(row.sha256(), "no document: the archive declared no parsable member with content");
                        continue;
                    }
                    var parsed = read.stream().map(CorpusDocuments.ReadDocument::model).toList();
                    var mismatch = versionMismatch(row, parsed);
                    if (mismatch != null) {
                        outcomes.put(row.sha256(), mismatch);
                        continue;
                    }
                    for (var document : read) {
                        var model = document.model();
                        if ("true".equals(model.properties().get("sbml.reducedRead"))) {
                            // A reduced document still ingests, so a silent reduction would be counted
                            // as a full read - the report has to say what the read lost (CORP-9).
                            reducedReads.put(model.reference().fileId(), droppedDescription(model));
                        }
                        if ("read".equals(model.properties().get("cellml.externalCmeta"))) {
                            // The parser writes this key only when the resolver's cmeta produced an
                            // annotation, so the set measures the resolution instead of inferring it.
                            externalCmeta.add(model.reference().fileId());
                        }
                        // The copy is measured, not written: storing ~45 MB of text had already charged
                        // ~330 MiB of the container's 344.4 MiB transaction pool (2026-11-25), and the
                        // report needs the SourceDocument.from facts, not 151 MB of xmlDoc here.
                        repository.insert(mapper.toGraph(model));
                        storedDocuments.add(new CorpusCoverage.StoredCopy(
                                model.reference().fileId(),
                                document.source().originalBytes(),
                                document.source().truncated()));
                    }
                    countedDocuments.addAll(parsed);
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
            var stated = CorpusCoverage.statedVersions(countedDocuments);

            var report = CorpusCoverage.report(
                    labels, relationships, stated.byVersion(), stated.unversioned(), strata, reducedReads,
                    externalCmeta, notYetSeen, storedDocuments);
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target", "corpus-report.md"),
                    "# Corpus coverage (CORP-4)\n\n" + report);

            assertTrue(report.contains("## Reduced reads"), report);
            assertTrue(report.contains("## External cmeta"), report);
            assertTrue(report.contains("## Stored documents"), report);
            assertTrue(report.contains("## Versions outside the claim"), report);
            assertEquals(countedDocuments.size(), storedDocuments.size(),
                    "every document the lane parsed must be measured against the storage ceiling (CORP-16)");

            assertCuratedRowsAllIngested(manifest, outcomes);
            assertNonCuratedFailuresAreSkipped(manifest, outcomes);
            assertEverySidecarReachedItsModel(manifest, externalCmeta);
            assertSkipsStillReproduce(manifest, outcomes);
            assertEquals(List.of(), CorpusCoverage.gaps(labels, relationships, stated.byVersion(), notYetSeen),
                    "the report names every uncovered construct; this list must be empty:\n" + report);
            assertEquals(List.of(), staleExplanations(notYetSeen, labels, relationships, stated.byVersion()),
                    "a not-yet-seen row for a construct the sample now covers would hide a future regression");
        }
    }

    /**
     * What a reduced read cost, as one cell of the report: the namespaces whose elements were dropped,
     * the number of annotation blocks nested inside another annotation (CORP-9, CORP-13), and the number
     * of `fbc:not` operands `jsbml-fbc` cannot read (FBC-4).
     */
    private static String droppedDescription(ParsedModel model) {
        var parts = new ArrayList<String>();
        var namespaces = model.properties().getOrDefault("sbml.droppedNamespaces", "");
        if (!namespaces.isEmpty()) {
            parts.add(namespaces);
        }
        var nested = model.properties().get("sbml.droppedNestedAnnotations");
        if (nested != null) {
            parts.add(nested + " nested annotation(s)");
        }
        var fbcNot = model.properties().get("sbml.droppedFbcNot");
        if (fbcNot != null) {
            parts.add(fbcNot + " fbc:not association(s)");
        }
        return String.join("; ", parts);
    }

    private static void assertCuratedRowsAllIngested(CorpusManifest manifest, Map<String, String> outcomes) {
        var failures = manifest.rows().stream()
                // A sidecar row never gets an outcome of its own, so counting it here would report
                // every harvested sidecar as a failed sample (CORP-10).
                .filter(row -> row.role() == CorpusManifest.Role.MAIN)
                .filter(row -> row.curation() == CorpusManifest.Curation.CURATED)
                .filter(row -> !"ok".equals(outcomes.get(row.sha256())))
                .map(row -> row.file() + " -> " + outcomes.get(row.sha256()))
                .toList();
        assertTrue(failures.isEmpty(), "a curated sample must ingest; no skip is legal for it:\n" + String.join("\n", failures));
    }

    private static void assertNonCuratedFailuresAreSkipped(CorpusManifest manifest, Map<String, String> outcomes) {
        var skipped = manifest.skips().stream().map(CorpusManifest.Skip::sha256).collect(java.util.stream.Collectors.toSet());
        var unexplained = manifest.rows().stream()
                .filter(row -> row.role() == CorpusManifest.Role.MAIN)
                .filter(row -> row.curation() == CorpusManifest.Curation.NON_CURATED)
                .filter(row -> !"ok".equals(outcomes.get(row.sha256())))
                .filter(row -> !skipped.contains(row.sha256()))
                .map(row -> row.file() + " -> " + outcomes.get(row.sha256()))
                .toList();
        assertTrue(unexplained.isEmpty(),
                "a non-curated failure must be recorded in skip.tsv with a reason:\n" + String.join("\n", unexplained));
    }

    /**
     * Every harvested {@code @@cmeta} sidecar must have been read by its model (CORP-10). A sidecar that
     * no document enriched has no other symptom: the model still ingests, the report still says
     * "None", and the harvest has silently bought nothing - which is the hole this assertion closes.
     */
    private static void assertEverySidecarReachedItsModel(CorpusManifest manifest, Set<String> externalCmeta) {
        var unread = manifest.rows().stream()
                .filter(row -> row.role() == CorpusManifest.Role.SIDECAR)
                .filter(row -> !externalCmeta.contains(row.id()))
                .map(row -> row.file() + " (document " + row.id() + ")")
                .toList();
        assertTrue(unread.isEmpty(),
                "a harvested @@cmeta sidecar was not read, so its model was not enriched from it: "
                        + String.join(", ", unread));
    }

    private static void assertSkipsStillReproduce(CorpusManifest manifest, Map<String, String> outcomes) {
        var stale = manifest.skips().stream()
                .filter(skip -> "ok".equals(outcomes.get(skip.sha256())))
                .map(CorpusManifest.Skip::sha256)
                .toList();
        assertTrue(stale.isEmpty(), "these skips no longer reproduce, so remove them: " + stale);
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
            Map<String, Set<String>> versions) {
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
                case CorpusCoverage.VERSION -> CorpusCoverage.documentCount(versions, key) > 0;
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

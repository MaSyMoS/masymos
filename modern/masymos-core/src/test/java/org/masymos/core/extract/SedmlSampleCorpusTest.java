package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * The workspace's SED-ML corpus: 38 {@code .xml} documents exported from BioModels (plus
 * {@code sedml.zip}, whose 38 entries carry exactly the same file names as the loose files). Until
 * now nothing read them, so they looked like coverage without being coverage.
 *
 * <p>Every document must parse, report the level and version its own {@code <sedML>} root declares,
 * and produce at least one task and one output, and the corpus must not silently shrink below 35
 * files. The expected level and version are read from the document, not asserted as a literal:
 * {@code TEST-003} measured that a literal {@code 1}/{@code 1} turns the unit suite red for a data
 * change the moment a Level 2 document joins the corpus, which is a fact about the sample set and
 * not about the parser. The corpus is read-only and is not modified; this test only reads it, and it
 * skips with a stated reason in a checkout that does not carry it. Tests never use the network: the
 * model reference points at an inert {@code example.org} URI and the parser is not asked to resolve
 * it.
 *
 * <p>The checkout is located the way {@code org.masymos.core.build.RepositoryEncodingTest} does it
 * (upward search for a {@code samples} directory beside the {@code masymos-modern} parent pom,
 * either at the root or under {@code modern/}), because the working directory of a Surefire test is
 * the module, not the repository root. That marker pair carries no {@code AGENTS.md}, so the same
 * test finds the root in the publish repository. Without a checkout the test skips with a stated
 * assumption instead of failing.
 */
class SedmlSampleCorpusTest {

    private static final String SAMPLE_DIRECTORY = "sedml";
    private static final int MINIMUM_CORPUS_SIZE = 35;
    private static final Pattern SEDML_ROOT = Pattern.compile("<(?:[A-Za-z0-9_]+:)?sedML\\b[^>]*>");
    private static final Pattern LEVEL = Pattern.compile("\\blevel\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern VERSION = Pattern.compile("\\bversion\\s*=\\s*\"([^\"]*)\"");

    @Test
    void everySampleSedmlDocumentParsesToItsDeclaredLevelAndVersion() throws IOException {
        var root = repositoryRoot();
        Assumptions.assumeTrue(root != null, "not a source checkout: the sample corpus needs the repository root");

        var directory = root.resolve("samples").resolve(SAMPLE_DIRECTORY);
        Assumptions.assumeTrue(Files.isDirectory(directory), "sample corpus not present: " + directory);

        var documents = xmlDocuments(directory);
        assertTrue(documents.size() >= MINIMUM_CORPUS_SIZE,
                "samples/" + SAMPLE_DIRECTORY + " must keep at least " + MINIMUM_CORPUS_SIZE
                        + " .xml files, found " + documents.size() + " in " + directory);

        for (Path document : documents) {
            var name = document.getFileName().toString();
            var parsed = parse(document, name);

            assertEquals(declared(document, LEVEL, "level"), parsed.properties().get("sedml.level"),
                    name + ": the parser must report the level the document declares");
            assertEquals(declared(document, VERSION, "version"), parsed.properties().get("sedml.version"),
                    name + ": the parser must report the version the document declares");
            assertTrue(hasEntity(parsed, EntityType.SEDML_TASK), name + ": must yield at least one task");
            assertFalse(parsed.entities().stream()
                            .filter(entity -> entity.type() == EntityType.SEDML_OUTPUT)
                            .toList()
                            .isEmpty(),
                    name + ": must yield at least one output");
        }
    }

    /**
     * The attribute the document's own root element declares. Read as text rather than by parsing,
     * so the expectation does not come from the code under test; a document whose root is missing or
     * does not declare the attribute fails the test instead of comparing against a guess.
     */
    private static String declared(Path document, Pattern attribute, String name) throws IOException {
        var text = Files.readString(document);
        var root = SEDML_ROOT.matcher(text);
        if (!root.find()) {
            throw new AssertionError(document.getFileName() + ": no <sedML> root element to read " + name + " from");
        }
        var value = attribute.matcher(root.group());
        if (!value.find()) {
            throw new AssertionError(document.getFileName() + ": the <sedML> root declares no " + name);
        }
        return value.group(1);
    }

    private static List<Path> xmlDocuments(Path directory) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .sorted()
                    .toList();
        }
    }

    private static ParsedModel parse(Path document, String fileId) throws IOException {
        var reference = new ModelReference(
                fileId,
                URI.create("https://example.org/models/" + fileId),
                ModelType.SEDML
        );
        try (InputStream stream = Files.newInputStream(document)) {
            return new SedmlModelParser().parse(new ModelSource(reference), stream);
        }
    }

    private static boolean hasEntity(ParsedModel parsed, EntityType type) {
        return parsed.entities().stream().anyMatch(entity -> entity.type() == type);
    }

    /**
     * The checkout root: the first ancestor holding a {@code samples} directory and the
     * {@code masymos-modern} build, found either as {@code pom.xml} at that same level or as
     * {@code modern/pom.xml}; {@code null} when the tests run outside a checkout.
     *
     * <p>Same locate step as {@code org.masymos.core.build.RepositoryEncodingTest}, deliberately
     * copied rather than shared, because the two tests must stay independently runnable. It does
     * not key on {@code AGENTS.md}, which the publish repository does not carry: a gate that
     * omitted the marker would skip there while looking green.</p>
     */
    private static Path repositoryRoot() {
        var candidate = Path.of("").toAbsolutePath();
        for (var depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("samples")) && isModernParentPom(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private static boolean isModernParentPom(Path candidate) {
        var atRoot = candidate.resolve("pom.xml");
        if (Files.isRegularFile(atRoot) && declaresModernParentPom(atRoot)) {
            return true;
        }
        var underModern = candidate.resolve("modern").resolve("pom.xml");
        return Files.isRegularFile(underModern) && declaresModernParentPom(underModern);
    }

    private static boolean declaresModernParentPom(Path pom) {
        try {
            return Files.readString(pom).contains("<artifactId>masymos-modern</artifactId>");
        } catch (IOException exception) {
            return false;
        }
    }
}

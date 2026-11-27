package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.masymos.core.archive.CombineArchiveReader;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * No XML payload reaches a parser as markup, and nothing in a payload is fetched or expanded.
 *
 * <p>{@code TEST-007} measured the hole this closes: the hardened factories were configured but no
 * test fed any of them a payload, so removing a guard left the suite green. The property is asserted
 * at every place the parser package builds a hardened factory - the eight sites are enumerated in
 * {@link #hardenedSites()} with the symbol that holds each one:</p>
 *
 * <ul>
 *   <li>{@code SbmlModelParser.hardenedFactory} - the Level 1 Version 1 species fallback's only DOM
 *       read;</li>
 *   <li>{@code SedmlModelParser.parse}, {@code CellmlModelParser.parse} - the parsers' own DOM
 *       reads;</li>
 *   <li>{@code OwlSerialisation.detect} - the sniffer, and {@code OwlOntologyParser.parse} - the DOM
 *       read behind it;</li>
 *   <li>{@code CombineArchiveReader.manifestEntries} - the manifest read;</li>
 *   <li>{@code FileCellmlImportResolver.parseModel} - the import target read;</li>
 *   <li>{@code UnsupportedSbmlNamespaces.stripped} - the one StAX factory, guarded with
 *       {@code SUPPORT_DTD} and {@code IS_SUPPORTING_EXTERNAL_ENTITIES} off instead of the JAXP
 *       features.</li>
 * </ul>
 *
 * <p>Each site is fed four documents: a DOCTYPE with an internal subset, an entity reference to a
 * file whose content is {@link #SECRET} (the XXE), and an external DTD by {@code SYSTEM} and by
 * {@code PUBLIC}. Three assertions hold for every site and payload: the secret never appears in the
 * outcome (nothing was expanded or read), and the site's own refusal shape is met - which differs per
 * site, because a parser wraps the {@code SAXParseException}, the archive reader wraps it again, the
 * resolver reports a closed-vocabulary reason and the StAX path accepts the document without the
 * DTD. A payload-free control document must be read first, so a site that refuses everything for an
 * unrelated reason cannot pass. {@link #anUnguardedFactoryExpandsTheSamePayload} proves the assertions
 * can fail: the same entity reference is expanded by a factory with no guards.</p>
 *
 * <p>The DTD URLs point at a closed loopback port, so a removed guard fails the test at once instead
 * of waiting for a network timeout. The {@code [Fatal Error]} lines on stderr are the JAXP default
 * error handler reporting the refusal - the parsers do not install one, and a quiet log is not worth
 * changing production behaviour for.</p>
 */
class XmlHardeningTest {
    /** Content of the file the entity payload references; present only if something expanded it. */
    private static final String SECRET = "XXE_PAYLOAD_MARKER_20261123";

    /**
     * An entity declared inline rather than fetched. The StAX read is the one site where this is the
     * only observable half of the hardening: its DOCTYPE is skipped either way, so what a removed
     * {@code SUPPORT_DTD} changes is whether the reference expands into the output.
     */
    private static final String EXPANDED = "EXPANDED_ENTITY_MARKER_20261123";

    /** The namespace a confusion payload claims with a URI that must never reach a result. */
    private static final String CONFUSED_NAMESPACE = "http://probe.invalid/xml-hardening";

    private static final String SBML_NAMESPACE = "http://www.sbml.org/sbml/level3/version1/core";
    private static final String CELLML_NAMESPACE = "http://www.cellml.org/cellml/2.0#";
    private static final String SEDML_NAMESPACE = "http://sed-ml.org/";
    private static final String RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String MANIFEST_NAMESPACE = "http://identifiers.org/combine.specifications/omex-manifest";

    @TempDir
    Path work;

    /**
     * What a site is fed. The four DTD-bearing payloads are {@code TEST-007}'s (a), (b) and (c) twice
     * over - the two spellings of an external DTD - and {@link #NAMESPACE_CONFUSION} is (d).
     */
    private enum Payload {
        PLAIN,
        DOCTYPE,
        ENTITY,
        INTERNAL_ENTITY,
        EXTERNAL_DTD_SYSTEM,
        EXTERNAL_DTD_PUBLIC,
        NAMESPACE_CONFUSION
    }

    /**
     * One hardened read: the document it accepts, how it is driven, and what its refusal looks like.
     *
     * @param name the site, as the failure message names it
     * @param document renders a payload into the dialect this site reads
     * @param read drives the production code and describes the outcome; never throws, because the
     *     refusal itself is the observation
     * @param parsed holds for a payload-free document, so a site that rejects everything cannot pass
     * @param rejection holds for the outcome of a DTD-bearing payload, which differs per site and, at
     *     the StAX site, per payload
     * @param confusion holds for the outcome of the namespace-confusion payload
     */
    private record Site(
            String name,
            BiFunction<Payload, Path, byte[]> document,
            BiFunction<byte[], Path, String> read,
            Predicate<String> parsed,
            BiPredicate<String, Payload> rejection,
            Predicate<String> confusion
    ) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hardenedSites")
    void everyHardenedFactoryRejectsDoctypeAndEntityPayloads(Site site) {
        var control = site.read().apply(site.document().apply(Payload.PLAIN, work), work);
        assertTrue(site.parsed().test(control), () -> site.name()
                + " does not read a payload-free document, so its refusals prove nothing: " + control);

        for (var payload : List.of(
                Payload.DOCTYPE,
                Payload.ENTITY,
                Payload.INTERNAL_ENTITY,
                Payload.EXTERNAL_DTD_SYSTEM,
                Payload.EXTERNAL_DTD_PUBLIC)) {
            var outcome = site.read().apply(site.document().apply(payload, work), work);
            assertFalse(outcome.contains(SECRET),
                    () -> site.name() + " expanded or fetched " + payload + ": " + outcome);
            assertFalse(outcome.contains(EXPANDED),
                    () -> site.name() + " expanded the declared entity of " + payload + ": " + outcome);
            assertTrue(site.rejection().test(outcome, payload),
                    () -> site.name() + " accepted " + payload + " instead of refusing it: " + outcome);
        }
    }

    /**
     * A document whose root claims a namespace that is not the site's own either fails the read or
     * contributes no model content. It does <b>not</b> have to be refused: the CellML and SED-ML
     * parsers read local names and record the declared namespace, so today such a document parses
     * with an empty entity list and {@code cellml.namespace}/{@code sedml.namespace} set to the
     * payload's URI - recorded as the deferred row {@code T035-1}, closed by {@code T-036}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("hardenedSites")
    void aNamespaceConfusionPayloadNeverBecomesModelContent(Site site) {
        var outcome = site.read().apply(site.document().apply(Payload.NAMESPACE_CONFUSION, work), work);
        assertTrue(site.confusion().test(outcome),
                () -> site.name() + " turned the payload namespace into content: " + outcome);
    }

    /**
     * The negative control: the same entity reference, the same secret file, no guards. If this does
     * not leak, the refusals above are attributable to something other than the hardening.
     */
    @Test
    void anUnguardedFactoryExpandsTheSamePayload() throws Exception {
        var payload = document(Payload.ENTITY, work, SBML_NAMESPACE, "<probe xmlns=\"%s\">#BODY#</probe>");

        var parsed = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(payload));

        assertTrue(parsed.getDocumentElement().getTextContent().contains(SECRET),
                "the payload must leak through an unguarded factory, or the refusals in this class prove nothing");
    }

    private static Stream<Site> hardenedSites() {
        return Stream.of(
                site("SbmlModelParser.hardenedFactory (the Level 1 Version 1 species fallback)",
                        SBML_NAMESPACE,
                        "<sbml xmlns=\"%s\" level=\"3\" version=\"1\">#BODY#</sbml>",
                        XmlHardeningTest::readWithSbmlFactory,
                        outcome -> outcome.startsWith("read "),
                        (outcome, payload) -> outcome.contains("SAXParseException"),
                        // A DOM factory does not guess a namespace: it parses the foreign one as
                        // declared, and the payload's URI stays in the document, never in the graph.
                        outcome -> outcome.startsWith("read ")),

                site("SedmlModelParser.parse",
                        SEDML_NAMESPACE,
                        "<sedML xmlns=\"%s\" level=\"1\" version=\"1\">#BODY#</sedML>",
                        (payload, path) -> parseSedml(payload),
                        outcome -> outcome.startsWith("read "),
                        (outcome, payload) -> outcome.contains("ModelParseException")
                                && outcome.contains("SAXParseException"),
                        // Refused once T-036 adds the namespace check; until then the document parses
                        // and must contribute no entity of any kind.
                        outcome -> outcome.contains("ModelParseException")
                                || (outcome.startsWith("read ") && outcome.contains("entities=[]"))),

                site("CellmlModelParser.parse",
                        CELLML_NAMESPACE,
                        "<model xmlns=\"%s\" name=\"probe\">#BODY#</model>",
                        (payload, path) -> parseCellml(payload),
                        outcome -> outcome.startsWith("read "),
                        (outcome, payload) -> outcome.contains("ModelParseException")
                                && outcome.contains("SAXParseException"),
                        outcome -> outcome.contains("ModelParseException")
                                || (outcome.startsWith("read ") && outcome.contains("entities=[]"))),

                site("OwlSerialisation.detect (the sniffer in front of the OWL DOM read)",
                        RDF_NAMESPACE,
                        "<rdf:RDF xmlns:rdf=\"%s\">#BODY#</rdf:RDF>",
                        (payload, path) -> "read " + OwlSerialisation.detect(payload),
                        outcome -> outcome.equals("read RDF_XML"),
                        // The sniffer parses to classify and reports a document it cannot parse as
                        // unrecognised: the DOCTYPE never becomes a tree it could classify.
                        (outcome, payload) -> outcome.equals("read UNRECOGNISED"),
                        // An RDF root in any other namespace is not RDF/XML, and saying so is the
                        // whole point of the namespace check.
                        outcome -> outcome.equals("read UNRECOGNISED")),

                site("OwlOntologyParser.parse (the DOM read behind the sniffer)",
                        RDF_NAMESPACE,
                        "<rdf:RDF xmlns:rdf=\"%s\">#BODY#</rdf:RDF>",
                        (payload, path) -> parseOwl(payload),
                        outcome -> outcome.startsWith("read "),
                        // Refused by the sniffer, so the cause is the unrecognised serialisation and
                        // not a SAX failure - a document that is not XML cannot be read as RDF/XML.
                        (outcome, payload) -> outcome.contains("ModelParseException"),
                        outcome -> outcome.contains("ModelParseException")
                                && outcome.contains("expected RDF/XML")),

                site("CombineArchiveReader.manifestEntries",
                        MANIFEST_NAMESPACE,
                        "<omexManifest xmlns=\"%s\"><content location=\".\"/>#BODY#</omexManifest>",
                        XmlHardeningTest::readArchive,
                        outcome -> outcome.startsWith("read "),
                        (outcome, payload) -> outcome.contains("CombineArchiveException")
                                && outcome.contains("SAXParseException"),
                        // The manifest namespace is checked by name, so this is refused outright.
                        outcome -> outcome.contains("CombineArchiveException")
                                && outcome.contains("unexpected namespace")),

                site("FileCellmlImportResolver.parseModel",
                        CELLML_NAMESPACE,
                        "<model xmlns=\"%s\" name=\"probe\"><component name=\"c\">#BODY#</component></model>",
                        XmlHardeningTest::readImportTarget,
                        outcome -> outcome.startsWith("resolved "),
                        // The resolver never fails a model: a target it cannot parse is the closed
                        // vocabulary's parse-failed, which is how a caller sees the refusal.
                        (outcome, payload) -> outcome.equals("unresolved parse-failed"),
                        // The component lookup matches any namespace on purpose, so the target is
                        // still found; what must not happen is the payload's URI becoming part of it
                        // (deferred as T035-1: the lookup does not require the CellML namespace).
                        outcome -> outcome.startsWith("resolved ") && !outcome.contains(CONFUSED_NAMESPACE)),

                site("UnsupportedSbmlNamespaces.stripped (the one StAX read)",
                        SBML_NAMESPACE,
                        "<sbml xmlns=\"%s\" level=\"3\" version=\"1\">#BODY#</sbml>",
                        XmlHardeningTest::readStripped,
                        outcome -> outcome.startsWith("read "),
                        // SUPPORT_DTD off means the DTD is skipped rather than refused: an entity
                        // reference then has nothing to expand and fails the read, while a DTD with no
                        // reference is read without being copied to the output. With external entities
                        // off the DTD is never fetched, which shows as a clean read.
                        (outcome, payload) -> switch (payload) {
                            case ENTITY, INTERNAL_ENTITY -> outcome.contains("refused XMLStreamException");
                            default -> !outcome.contains("refused XMLStreamException");
                        },
                        // A foreign-namespace subtree (the root included) is what this read drops, so
                        // the URI can only appear here if the drop stopped working.
                        outcome -> !outcome.contains(CONFUSED_NAMESPACE))
        );
    }

    private static Site site(
            String name,
            String namespace,
            String template,
            BiFunction<byte[], Path, String> read,
            Predicate<String> parsed,
            BiPredicate<String, Payload> rejection,
            Predicate<String> confusion
    ) {
        return new Site(
                name,
                (payload, path) -> document(payload, path, namespace, template),
                read,
                parsed,
                rejection,
                confusion
        );
    }

    /**
     * Renders one payload into a document the site would otherwise read: the prologue carries the
     * attack, and the template's {@code #BODY#} is where an entity reference has to appear for it to
     * be expanded at all.
     */
    private static byte[] document(Payload payload, Path work, String namespace, String template) {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        switch (payload) {
            case DOCTYPE -> xml.append("<!DOCTYPE probe [<!ELEMENT probe ANY>]>\n");
            case ENTITY -> xml.append("<!DOCTYPE probe [<!ENTITY probe SYSTEM \"")
                    .append(secretUri(work))
                    .append("\">]>\n");
            case INTERNAL_ENTITY -> xml.append("<!DOCTYPE probe [<!ENTITY probe \"")
                    .append(EXPANDED)
                    .append("\">]>\n");
            case EXTERNAL_DTD_SYSTEM -> xml.append("<!DOCTYPE probe SYSTEM \"http://127.0.0.1:9/probe.dtd\">\n");
            case EXTERNAL_DTD_PUBLIC -> xml.append(
                    "<!DOCTYPE probe PUBLIC \"-//PROBE//DTD Probe//EN\" \"http://127.0.0.1:9/probe.dtd\">\n");
            default -> {
                // PLAIN and NAMESPACE_CONFUSION differ in the root's namespace only.
            }
        }
        var effectiveNamespace = payload == Payload.NAMESPACE_CONFUSION ? CONFUSED_NAMESPACE : namespace;
        var body = payload == Payload.ENTITY || payload == Payload.INTERNAL_ENTITY ? "&probe;" : "";
        xml.append(template.formatted(effectiveNamespace).replace("#BODY#", body));
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The file the entity payload points at, created on first use so the expansion has content. */
    private static String secretUri(Path work) {
        try {
            var file = work.resolve("probe-secret.txt");
            if (!Files.exists(file)) {
                Files.writeString(file, SECRET, StandardCharsets.UTF_8);
            }
            return file.toUri().toString();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The factory itself, with a handler that reports the refusal as an exception and not on stderr. */
    private static String readWithSbmlFactory(byte[] payload, Path ignored) {
        try {
            var builder = SbmlModelParser.hardenedFactory().newDocumentBuilder();
            builder.setErrorHandler(rethrowing());
            var document = builder.parse(new ByteArrayInputStream(payload));
            return "read " + document.getDocumentElement().getTagName();
        } catch (Exception failure) {
            return describe(failure);
        }
    }

    private static String parseSedml(byte[] payload) {
        try {
            var parsed = new SedmlModelParser().parse(source("probe.sedml", ModelType.SEDML), stream(payload));
            return "read " + parsed;
        } catch (Exception failure) {
            return describe(failure);
        }
    }

    private static String parseCellml(byte[] payload) {
        try {
            var parsed = new CellmlModelParser().parse(source("probe.cellml", ModelType.CELLML), stream(payload));
            return "read " + parsed;
        } catch (Exception failure) {
            return describe(failure);
        }
    }

    private static String parseOwl(byte[] payload) {
        try {
            var parsed = new OwlOntologyParser().parse(source("probe.owl", ModelType.OWL), stream(payload));
            return "read " + parsed;
        } catch (Exception failure) {
            return describe(failure);
        }
    }

    /** The manifest payload has to arrive inside an archive, because that is the only way in. */
    private static String readArchive(byte[] manifest, Path ignored) {
        try {
            var archive = new CombineArchiveReader().read(new ByteArrayInputStream(archive(manifest)));
            return "read " + archive.entries();
        } catch (Exception failure) {
            return describe(failure);
        }
    }

    /** The import target payload has to arrive as a file, because the resolver refuses a URI scheme. */
    private static String readImportTarget(byte[] payload, Path work) {
        try {
            var target = work.resolve("import-target.cellml");
            Files.write(target, payload);
            var resolution = new FileCellmlImportResolver(work)
                    .resolve("import-target.cellml", "c", source("probe.cellml", ModelType.CELLML));
            return resolution instanceof CellmlImportResolver.Unresolved unresolved
                    ? "unresolved " + unresolved.reason()
                    : "resolved " + resolution;
        } catch (Exception failure) {
            return describe(failure);
        }
    }

    private static String readStripped(byte[] payload, Path ignored) {
        try {
            var stripped = UnsupportedSbmlNamespaces.stripped(payload);
            return "read " + new String(stripped.document(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return describe(failure, XMLStreamException.class);
        }
    }

    private static byte[] archive(byte[] manifest) {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.xml"));
            zip.write(manifest);
            zip.closeEntry();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return bytes.toByteArray();
    }

    private static ModelSource source(String fileId, ModelType type) {
        return new ModelSource(new ModelReference(fileId, URI.create("https://example.org/" + fileId), type));
    }

    private static ByteArrayInputStream stream(byte[] payload) {
        return new ByteArrayInputStream(payload);
    }

    /**
     * The outcome of a failed read as text: the class chain, with the outermost message because the
     * parsers put the source URI there. Xerces' own message is locale-dependent, so no assertion in
     * this class matches on it - the exception type is the observable.
     */
    private static String describe(Throwable failure) {
        var text = new StringBuilder("threw ");
        var current = failure;
        for (var depth = 0; current != null && depth < 8; depth++) {
            if (depth > 0) {
                text.append(" caused by ");
            }
            text.append(current.getClass().getName());
            if (depth == 0 && current.getMessage() != null) {
                text.append(": ").append(current.getMessage().replace('\n', ' '));
            }
            current = current.getCause();
        }
        return text.toString();
    }

    /**
     * The same description with a marker for the refusal's own kind. The StAX implementation on the
     * classpath is Woodstox, whose parsing failure is a {@code WstxParsingException} - a subclass of
     * {@code XMLStreamException} - so the marker has to be an instance check and not the class name.
     */
    private static String describe(Throwable failure, Class<? extends Exception> type) {
        for (var current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return "refused " + type.getSimpleName() + ": " + describe(failure);
            }
        }
        return describe(failure);
    }

    private static ErrorHandler rethrowing() {
        return new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {
                // A warning is not this site's business; only the rejection below is.
            }

            @Override
            public void error(SAXParseException exception) throws SAXParseException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXParseException {
                throw exception;
            }
        };
    }
}

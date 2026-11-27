package org.masymos.core.extract;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;

/**
 * Resolves CellML imports against a local directory: {@code href} is treated as a path relative to
 * {@code root}.
 *
 * <p>Only a plain relative path is accepted. An href with a URI scheme ({@code http:},
 * {@code file:}, ...) or an absolute path is refused as {@code not-found} without touching the file
 * system, so a document can never steer the resolver outside {@code root} or onto the network.
 *
 * <p><b>Resolution depth is 1.</b> The resolved document is read for its own
 * {@code <component>} element only; imports declared by that document are not followed.
 *
 * <p>Reads are capped at {@value #MAX_BYTES} bytes ({@code too-large} beyond it) and the document is
 * parsed with the same hardened XML factory the main parser uses (no doctype, no external entities,
 * no external DTD or schema access).
 *
 * <p>{@link #resolveUnits} (CML-1) reduces the target's {@code units} declaration in the target's own
 * scope through the parser's own unit layer, so an imported unit gets the same canonical key a local
 * declaration would. The target's own imports are not followed either - resolution depth is one for
 * units too.
 */
public final class FileCellmlImportResolver implements CellmlImportResolver {
    /** Hard cap on the size of a resolved import target. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final String CMETA_NAMESPACE = "http://www.cellml.org/metadata/1.0#";

    private final Path root;

    public FileCellmlImportResolver(Path root) {
        this.root = root.normalize();
    }

    @Override
    public ImportTarget resolve(String href, String componentRef, ModelSource source) {
        var parsed = parseModel(href);
        if (parsed instanceof DocumentFailure failure) {
            return new Unresolved(failure.reason());
        }
        var documentRoot = ((DocumentModel) parsed).model();
        var componentName = componentRef == null ? "" : componentRef.trim();
        var components = documentRoot.getElementsByTagNameNS("*", "component");
        for (var index = 0; index < components.getLength(); index++) {
            var component = (Element) components.item(index);
            if (componentName.equals(component.getAttribute("name"))) {
                return new Resolved(
                        componentName,
                        cmetaId(component),
                        variables(component)
                );
            }
        }
        return new Unresolved("component-missing");
    }

    /**
     * CML-1: an imported {@code <units>} declaration is reduced in the target document's own scope,
     * by the same {@link org.masymos.core.units.UnitResolver} path a local declaration takes. That
     * is why an imported metre and a local metre end up as one node: the key is the reduction, not
     * the name.
     */
    @Override
    public UnitsTarget resolveUnits(String href, String unitsRef, ModelSource source) {
        var reference = unitsRef == null ? "" : unitsRef.trim();
        if (reference.isEmpty()) {
            return new Unresolved("units-missing");
        }
        var parsed = parseModel(href);
        if (parsed instanceof DocumentFailure failure) {
            return new Unresolved(failure.reason());
        }
        return CellmlModelParser.declaredUnits(((DocumentModel) parsed).model(), reference)
                .<UnitsTarget>map(unit -> new ResolvedUnits(reference, unit))
                .orElseGet(() -> new Unresolved("units-missing"));
    }

    /** The target document read once, or the closed-vocabulary reason it could not be. */
    private ParsedDocument parseModel(String href) {
        var relativePath = relativePath(href);
        if (relativePath == null) {
            return new DocumentFailure("not-found");
        }
        var target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return new DocumentFailure("not-found");
        }

        Element documentRoot;
        try (InputStream stream = Files.newInputStream(target)) {
            documentRoot = hardenedFactory().newDocumentBuilder().parse(new BoundedInputStream(stream, MAX_BYTES))
                    .getDocumentElement();
        } catch (TooLargeException exception) {
            return new DocumentFailure("too-large");
        } catch (org.xml.sax.SAXException | ParserConfigurationException exception) {
            return new DocumentFailure("parse-failed");
        } catch (IOException | RuntimeException exception) {
            return new DocumentFailure("unreadable");
        }
        if (documentRoot == null || !"model".equals(documentRoot.getLocalName())) {
            return new DocumentFailure("parse-failed");
        }
        return new DocumentModel(documentRoot);
    }

    /** Outcome of reading an import target: either its {@code <model>} root or a reason. */
    private sealed interface ParsedDocument permits DocumentModel, DocumentFailure {
    }

    private record DocumentModel(Element model) implements ParsedDocument {
    }

    private record DocumentFailure(String reason) implements ParsedDocument {
    }

    /**
     * Returns the href as a relative path, or {@code null} when it carries a scheme or is absolute.
     * The check is textual on purpose: {@link java.net.URI#getScheme()} treats {@code c:/tmp} as the
     * scheme {@code c}, and any colon before the first separator is exactly what must be refused.
     */
    private static Path relativePath(String href) {
        var value = href == null ? "" : href.trim();
        if (value.isEmpty() || value.indexOf(':') >= 0) {
            return null;
        }
        try {
            var path = Path.of(value);
            return path.isAbsolute() ? null : path;
        } catch (InvalidPathException exception) {
            return null;
        }
    }

    private static List<ImportVariable> variables(Element component) {
        var variables = new ArrayList<ImportVariable>();
        var children = component.getChildNodes();
        for (var index = 0; index < children.getLength(); index++) {
            if (children.item(index) instanceof Element child && "variable".equals(child.getLocalName())) {
                variables.add(new ImportVariable(
                        child.getAttribute("name"),
                        child.getAttribute("public_interface"),
                        child.getAttribute("private_interface"),
                        child.getAttribute("units")
                ));
            }
        }
        return List.copyOf(variables);
    }

    private static String cmetaId(Element element) {
        var value = element.getAttributeNS(CMETA_NAMESPACE, "id");
        if (value == null || value.isBlank()) {
            value = element.getAttribute("cmeta:id");
        }
        return value == null ? "" : value;
    }

    private static DocumentBuilderFactory hardenedFactory() throws javax.xml.parsers.ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    /** Thrown when the stream exceeds the byte cap; distinct from a genuine IO failure. */
    private static final class TooLargeException extends IOException {
        TooLargeException(long limit) {
            super("import target exceeds " + limit + " bytes");
        }
    }

    /** Counts bytes as they are pulled and aborts once the cap is exceeded. */
    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            var value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var count = super.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws TooLargeException {
            read += bytes;
            if (read > limit) {
                throw new TooLargeException(limit);
            }
        }
    }
}

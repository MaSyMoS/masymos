package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import org.sbml.jsbml.ext.fbc.FBCConstants;
import org.sbml.jsbml.xml.parsers.ParserManager;

/**
 * Rewrites an SBML document without the elements JSBML has no reading parser for.
 *
 * <p>Measured 2026-11-20 (`CORP-8`) on BioModels `BIOMD0000000899`, a CellDesigner-annotated Level 2
 * document: JSBML 1.6.1 reads an element in a namespace it knows no parser for as an opaque
 * {@code XMLNode}, and a core {@code <annotation>} nested inside such an element then hits the cast
 * at the end of the annotation branch ({@code XMLNode cannot be cast to Annotation}). The same
 * mechanism as `CORP-6`, but no package jar cures this one - CellDesigner is a manufacturer
 * namespace, not an SBML Level 3 package, and JSBML has no parser for it and never will.</p>
 *
 * <p>So the foreign elements are removed rather than the document refused. What they carry is not in
 * our graph vocabulary (package and manufacturer content is the stated gap, decision 43), the
 * original bytes stay the stored source document, and nothing JSBML can actually bind is touched: the
 * core namespaces, MathML and XHTML are kept always, and a Level 3 package namespace is kept only in
 * a Level 3 document, because the registration is global while the binding is per level.</p>
 *
 * <p>{@link #stripped(byte[])} also returns the namespaces it removed, because a reduced read has to
 * say so: before `CORP-9` (row `CORP7-12`) nothing recorded the retry, so the corpus lane counted a
 * stripped document as fully ingested and a reader could not tell that annotations in a manufacturer
 * namespace were never indexed (decision 43).</p>
 */
final class UnsupportedSbmlNamespaces {
    /** The parser table's key for the generic XML reader; a real namespace never looks like this. */
    private static final String ANY_XML = "anyXML";

    /** Every SBML core namespace begins with this; a document root outside it is not SBML. */
    static final String SBML_CORE_NAMESPACE_PREFIX = "http://www.sbml.org/sbml/level";

    /**
     * A rewritten document and what the rewrite cost: the namespaces whose elements were dropped, and
     * how many {@code <annotation>} blocks nested inside another annotation were dropped.
     *
     * <p>{@code namespaces} lists each dropped namespace URI once, in document order, so the caller can
     * name what the reduced read did not index.</p>
     */
    record Stripped(byte[] document, List<String> namespaces, int nestedAnnotations) {
    }

    private UnsupportedSbmlNamespaces() {
    }

    /**
     * The document without elements in unsupported namespaces: every such element is dropped with its
     * whole subtree, because a subtree that starts in an unknown namespace cannot be placed anywhere
     * else meaningfully. Namespace declarations and attributes are copied unchanged - an unused
     * declaration is legal XML, and dropping an attribute JSBML tolerates would be a silent edit.
     *
     * <p><b>Annotation content is kept.</b> JSBML reads an {@code <annotation>} as opaque XML instead of
     * dispatching it to the parser table, so a namespace with no registered parser inside an annotation
     * is not foreign - {@code rdf}, {@code dc}, {@code dc/terms} and both biomodels.net qualifier
     * namespaces all appear nowhere in that table and all belong to the document. Measured 2026-11-20
     * (`CORP-13`) on `BIOMD0000000899`: dropping them took 43 {@code rdf:RDF} subtrees and 25
     * {@code bqmodel} identifiers, and the parsed model arrived with 0 resources.</p>
     *
     * <p><b>One exception, and it is the crash.</b> A whole {@code <annotation>} nested inside another
     * {@code <annotation>} is what makes JSBML 1.6.1 cast an {@code XMLNode} to {@code Annotation}: the
     * inner element is read as opaque XML by the outer annotation's reader, so the end-of-annotation
     * branch finds an {@code XMLNode} on the stack. CellDesigner and COPASI write exactly that - a
     * second, tool-owned annotation inside the model's own - so a nested annotation is dropped with its
     * subtree. Keeping the RDF alone, or keeping all annotation content, each reintroduces the crash
     * because both keep the nested block.</p>
     *
     * <p>The dropped namespace URIs and the nested-annotation count are returned with the bytes, so the
     * caller can say that the read was reduced. Before `CORP-9` (row `CORP7-12`) nothing recorded the
     * retry: the corpus lane counted a stripped document as fully ingested, and a reader could not tell
     * that annotations in a manufacturer namespace were never indexed (decision 43).</p>
     */
    static Stripped stripped(byte[] document) throws XMLStreamException {
        var supported = ParserManager.getManager().getReadingParsers().keySet();
        var reader = inputFactory().createXMLStreamReader(new ByteArrayInputStream(document));
        var output = new ByteArrayOutputStream();
        var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8");
        var namespaces = new ArrayList<String>();
        var dropped = 0;
        var level = 0;
        var annotationDepth = 0;
        var nestedAnnotations = 0;
        try {
            while (reader.hasNext()) {
                var event = reader.next();
                if (dropped > 0) {
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        dropped++;
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        dropped--;
                    }
                    continue;
                }
                switch (event) {
                    case XMLStreamConstants.START_DOCUMENT -> writer.writeStartDocument("UTF-8", "1.0");
                    case XMLStreamConstants.END_DOCUMENT -> writer.writeEndDocument();
                    case XMLStreamConstants.START_ELEMENT -> {
                        if (level == 0 && "sbml".equals(reader.getLocalName())) {
                            level = declaredLevel(reader);
                        }
                        if (annotationDepth > 0
                                && isAnnotationElement(reader.getNamespaceURI(), reader.getLocalName())) {
                            // A nested annotation, not annotation content: it is the crash trigger,
                            // see the javadoc of stripped(...).
                            dropped = 1;
                            nestedAnnotations++;
                            continue;
                        }
                        var annotationContent = annotationDepth > 0
                                || isAnnotationElement(reader.getNamespaceURI(), reader.getLocalName());
                        if (!annotationContent && !isSupported(reader.getNamespaceURI(), supported, level)) {
                            dropped = 1;
                            var namespace = reader.getNamespaceURI();
                            if (namespace != null && !namespaces.contains(namespace)) {
                                namespaces.add(namespace);
                            }
                            continue;
                        }
                        startElement(reader, writer);
                        if (annotationContent) {
                            annotationDepth++;
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        writer.writeEndElement();
                        if (annotationDepth > 0) {
                            annotationDepth--;
                        }
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE, XMLStreamConstants.CDATA ->
                            writer.writeCharacters(reader.getText());
                    case XMLStreamConstants.COMMENT -> writer.writeComment(reader.getText());
                    case XMLStreamConstants.PROCESSING_INSTRUCTION ->
                            writer.writeProcessingInstruction(reader.getPITarget(), reader.getPIData());
                    default -> {
                        // DTD and entity events: DOCTYPE is not carried over, and external entities are
                        // disabled above, so there is nothing to copy.
                    }
                }
            }
        } finally {
            reader.close();
            writer.close();
        }
        return new Stripped(output.toByteArray(), List.copyOf(namespaces), nestedAnnotations);
    }

    /**
     * The one StAX factory this class reads with: no DTD, no external entities, coalescing on.
     *
     * <p>Shared on purpose - {@link #stripped(byte[])} and {@link #rootNamespace(byte[])} both use it,
     * so the hardening proven for the rewrite path holds for the namespace guard too and cannot drift
     * from it.</p>
     */
    private static XMLInputFactory inputFactory() {
        var inputFactory = XMLInputFactory.newFactory();
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        inputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return inputFactory;
    }

    /**
     * The namespace URI of the document's root element, or {@code null} when the root carries none.
     *
     * <p>Read through {@link #inputFactory()}, the same hardened factory {@link #stripped(byte[])}
     * uses, so a document cannot reach the network on this path either. Only the first
     * {@code START_ELEMENT} is inspected; malformed input surfaces as the factory's
     * {@link XMLStreamException}.</p>
     */
    static String rootNamespace(byte[] document) throws XMLStreamException {
        var reader = inputFactory().createXMLStreamReader(new ByteArrayInputStream(document));
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    return reader.getNamespaceURI();
                }
            }
            return null;
        } finally {
            reader.close();
        }
    }

    /**
     * How many {@code not} elements the document carries in an {@code fbc} namespace - the subtrees
     * {@code jsbml-fbc} 1.6.1 drops because it declares no {@code Not} type (measured 2026-11-24, row
     * {@code FBC2-2}).
     *
     * <p>Counted, not read: recovering the operand would mean a second parser for the association tree,
     * while the reader only needs to be told the document was read in reduced form ({@code FBC-4}, the
     * cheaper half). Read through {@link #inputFactory()}, like the other two walks here, so this path
     * cannot reach the network either.</p>
     */
    static int fbcNotCount(byte[] document) {
        var count = 0;
        XMLStreamReader reader = null;
        try {
            reader = inputFactory().createXMLStreamReader(new ByteArrayInputStream(document));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && FBCConstants.namespaces.contains(reader.getNamespaceURI())
                        // No constant exists for it, which is the same fact in another form: jsbml-fbc
                        // declares no Not type, so `not` is a bare local name here.
                        && "not".equals(reader.getLocalName())) {
                    count++;
                }
            }
            return count;
        } catch (XMLStreamException malformed) {
            // A walk that cannot finish belongs to a document the parse itself rejects; reporting zero
            // here keeps the one failure the parse already raises the only one a reader sees.
            return 0;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // Closing a reader that already failed changes nothing a caller can act on.
                }
            }
        }
    }

    /**
     * An {@code <annotation>} in the SBML core namespace - the element whose content JSBML keeps as
     * opaque XML instead of dispatching it to the parser table.
     */
    private static boolean isAnnotationElement(String namespace, String localName) {
        return "annotation".equals(localName)
                && namespace != null
                && namespace.startsWith(SBML_CORE_NAMESPACE_PREFIX);
    }

    /**
     * An element without a namespace is never foreign; a namespaced one must be one JSBML can read.
     *
     * <p>The registration alone is not enough: a parser registered for a Level 3 package is not bound
     * in a Level 2 document, measured 2026-11-20 on `BIOMD0000000899` - its `<layout:listOfLayouts>` is
     * in the Level 3 spelling of the layout namespace, the parser table lists that namespace, and the
     * Level 2 reader still holds it as an opaque `XMLNode`. So a Level 3 namespace counts as supported
     * only when the document is Level 3 or later.</p>
     */
    private static boolean isSupported(String namespace, Set<String> supported, int level) {
        if (namespace == null || namespace.isEmpty()) {
            return true;
        }
        if (!supported.contains(namespace) && !ANY_XML.equals(namespace)) {
            return false;
        }
        return level >= 3 || !namespace.contains("/level3/");
    }

    /** The document's `level` attribute, or 0 when the root states none. */
    private static int declaredLevel(XMLStreamReader reader) {
        for (var index = 0; index < reader.getAttributeCount(); index++) {
            if ("level".equals(reader.getAttributeLocalName(index))) {
                try {
                    return Integer.parseInt(reader.getAttributeValue(index).trim());
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static void startElement(XMLStreamReader reader, XMLStreamWriter writer) throws XMLStreamException {
        var namespace = reader.getNamespaceURI();
        var prefix = reader.getPrefix() == null ? "" : reader.getPrefix();
        if (namespace == null || namespace.isEmpty()) {
            writer.writeStartElement(reader.getLocalName());
        } else {
            writer.writeStartElement(prefix, reader.getLocalName(), namespace);
        }
        for (var index = 0; index < reader.getNamespaceCount(); index++) {
            var declaredPrefix = reader.getNamespacePrefix(index);
            var declaredUri = reader.getNamespaceURI(index);
            if (declaredUri == null || declaredUri.isEmpty()) {
                continue;
            }
            writer.writeNamespace(declaredPrefix == null ? "" : declaredPrefix, declaredUri);
        }
        for (var index = 0; index < reader.getAttributeCount(); index++) {
            var attributeNamespace = reader.getAttributeNamespace(index);
            var value = reader.getAttributeValue(index);
            if (attributeNamespace == null || attributeNamespace.isEmpty()) {
                writer.writeAttribute(reader.getAttributeLocalName(index), value);
            } else {
                var attributePrefix = reader.getAttributePrefix(index);
                writer.writeAttribute(attributePrefix == null ? "" : attributePrefix, attributeNamespace,
                        reader.getAttributeLocalName(index), value);
            }
        }
    }
}

package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Sniffs the serialisation of an OWL document from its content, never from its file name.
 *
 * <p>All four OWL 2 serialisations are accepted: RDF/XML is read directly, and Turtle, OWL/XML and
 * functional syntax are converted to RDF/XML by {@link OwlSerialisationConverter} (OWL-2). Content
 * that matches none of them is named as unrecognised rather than reported as a malformed RDF/XML
 * document.
 */
enum OwlSerialisation {
    RDF_XML,
    TURTLE,
    OWL_XML,
    FUNCTIONAL_SYNTAX,
    UNRECOGNISED;

    private static final String OWL_NAMESPACE = "http://www.w3.org/2002/07/owl#";
    private static final String RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final Pattern XML_DECLARATION = Pattern.compile(
            "(?ms)\\A\\s*<\\?xml\\s[^>]*\\?>\\s*(<!--.*?-->\\s*)*<");
    private static final Pattern TURTLE_PREFIX_DIRECTIVE =
            Pattern.compile("(?im)^\\s*(@prefix|@base)\\b");
    private static final Pattern SPARQL_STYLE_TURTLE_PREFIX = Pattern.compile(
            "(?im)^\\s*(prefix|base)\\s+[A-Za-z_][A-Za-z0-9_.-]*:|^\\s*(prefix|base)\\s*<"
    );
    private static final Pattern TURTLE_TRIPLE =
            Pattern.compile("(?m)<[^\\s<>]+>\\s+[A-Za-z_:][A-Za-z0-9_.:-]*|<[^\\s<>]+>\\s*\\.");
    private static final Pattern FUNCTIONAL_SYNTAX_PATTERN =
            Pattern.compile("(?m)^\\s*(Ontology|Prefix|Declaration|SubClassOf)\\s*\\(");

    /** The name a human uses for this serialisation, e.g. in a parse-failure message. */
    String displayName() {
        return switch (this) {
            case RDF_XML -> "RDF/XML";
            case TURTLE -> "Turtle";
            case OWL_XML -> "OWL/XML";
            case FUNCTIONAL_SYNTAX -> "OWL functional syntax";
            case UNRECOGNISED -> "unrecognised";
        };
    }

    static OwlSerialisation detect(byte[] content) {
        if (content == null || content.length == 0) {
            return UNRECOGNISED;
        }
        byte[] bytes = stripUtf8Bom(content);
        String text = new String(bytes, StandardCharsets.UTF_8);

        // An XML document need not begin with an <?xml?> declaration, so try the XML
        // classification first and only fall through when it is not an XML format.
        if (text.stripLeading().startsWith("<")) {
            var xml = detectXml(bytes);
            if (xml == RDF_XML || xml == OWL_XML) {
                return xml;
            }
        }
        if (TURTLE_PREFIX_DIRECTIVE.matcher(text).find() || SPARQL_STYLE_TURTLE_PREFIX.matcher(text).find()) {
            return TURTLE;
        }
        if (FUNCTIONAL_SYNTAX_PATTERN.matcher(text).find()) {
            return FUNCTIONAL_SYNTAX;
        }
        if (TURTLE_TRIPLE.matcher(text).find()) {
            return TURTLE;
        }
        return UNRECOGNISED;
    }

    /**
     * Distinguishes the XML serialisations by root element. A document that claims to be XML but does
     * not parse is reported as unrecognised, because no XML-based serialisation can be read from it.
     */
    private static OwlSerialisation detectXml(byte[] content) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var builder = factory.newDocumentBuilder();
            // Parsing is expected to fail here for content that is not XML at all; report through the
            // return value, not through stderr noise that looks like a build failure.
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // Not an error for a sniffing pass.
                }

                @Override
                public void error(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            var document = builder.parse(new ByteArrayInputStream(content));
            var root = document.getDocumentElement();
            if (root == null) {
                return UNRECOGNISED;
            }
            if ("RDF".equals(localName(root)) && RDF_NAMESPACE.equals(root.getNamespaceURI())) {
                return RDF_XML;
            }
            if ("Ontology".equals(localName(root)) && OWL_NAMESPACE.equals(root.getNamespaceURI())) {
                return OWL_XML;
            }
            return UNRECOGNISED;
        } catch (Exception exception) {
            return UNRECOGNISED;
        }
    }

    private static String localName(Element element) {
        var localName = element.getLocalName();
        if (localName != null) {
            return localName;
        }
        var nodeName = element.getNodeName();
        var colon = nodeName.indexOf(':');
        return colon >= 0 ? nodeName.substring(colon + 1) : nodeName;
    }

    static byte[] stripUtf8Bom(byte[] content) {
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            var stripped = new byte[content.length - 3];
            System.arraycopy(content, 3, stripped, 0, stripped.length);
            return stripped;
        }
        return content;
    }
}

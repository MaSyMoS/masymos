package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnnotationTextExtractorTest {

    private final AnnotationTextExtractor extractor = new AnnotationTextExtractor();

    @Test
    void stripsHtmlMarkupAndScriptBlocks() {
        var html = """
                <html><head><style>body{color:red}</style></head>
                <body><script>var secret = 'x';</script><h1>Homo sapiens</h1><p>Species&nbsp;page</p></body></html>
                """;

        var text = extractor.extract("text/html; charset=UTF-8", html);

        assertTrue(text.contains("Homo sapiens"), text);
        assertTrue(text.contains("Species page"), text);
        assertFalse(text.contains("secret"), text);
        assertFalse(text.contains("color:red"), text);
        assertFalse(text.contains("<"), text);
    }

    @Test
    void keepsXmlTextButDropsTags() {
        var text = extractor.extract("application/rdf+xml", "<rdf:RDF><rdfs:label>apoptosis</rdfs:label></rdf:RDF>");

        assertEquals("apoptosis", text);
    }

    @Test
    void extractsJsonScalarValues() {
        var text = extractor.extract("application/json", "{\"name\":\"apoptosis\",\"id\":\"GO:0006915\",\"tags\":[\"death\"]}");

        assertTrue(text.contains("apoptosis"), text);
        assertTrue(text.contains("GO:0006915"), text);
        assertTrue(text.contains("death"), text);
        assertFalse(text.contains("{"), text);
    }

    @Test
    void fallsBackToPlainTextForBrokenJson() {
        var text = extractor.extract("application/json", "{\"name\": broken");

        assertFalse(text.isBlank());
    }

    @Test
    void collapsesWhitespaceInPlainText() {
        assertEquals("a b c", extractor.extract("text/plain", "a  b\n\n\tc  "));
    }

    @Test
    void truncatesToConfiguredBound() {
        var bounded = new AnnotationTextExtractor(5);

        assertEquals("abcde", bounded.extract("text/plain", "abcdefghij"));
    }

    @Test
    void handlesEmptyBody() {
        assertEquals("", extractor.extract("text/html", null));
        assertEquals("", extractor.extract(null, "   "));
    }

    @Test
    void detectsFormatFromContentType() {
        assertEquals("html", extractor.formatOf("text/html"));
        assertEquals("json", extractor.formatOf("application/ld+json"));
        assertEquals("xml", extractor.formatOf("application/xml"));
        assertEquals("text", extractor.formatOf(null));
    }
}

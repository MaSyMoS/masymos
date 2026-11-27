package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.time.Duration;
import javax.xml.XMLConstants;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The SBML parser's only DOM read is the Level 1 Version 1 species fallback, and no document may make
 * it reach the network.
 *
 * <p>Asserted twice, because either half alone is weak: the two JAXP access properties are set to the
 * empty string (the configuration contract, which no DOCTYPE-free document can observe), and a
 * document that references an external DTD is refused inside a short timeout - so a socket attempt
 * fails the test instead of hanging it.
 */
class SbmlModelParserSecurityTest {

    @Test
    void aDocumentReferencingAnExternalDtdIsRefused() throws Exception {
        var factory = SbmlModelParser.hardenedFactory();

        assertEquals("", factory.getAttribute(XMLConstants.ACCESS_EXTERNAL_DTD),
                "the factory must name no protocol for an external DTD");
        assertEquals("", factory.getAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA),
                "the factory must name no protocol for an external schema");
        assertTrue(factory.getFeature("http://apache.org/xml/features/disallow-doctype-decl"),
                "a DOCTYPE is refused outright, so a DTD is never read");

        var referencing = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE sbml SYSTEM "http://127.0.0.1:9/never-answers.dtd">
                <sbml xmlns="http://www.sbml.org/sbml/level1" level="1" version="1"/>
                """;

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(SAXException.class,
                        () -> factory.newDocumentBuilder().parse(new InputSource(new StringReader(referencing)))),
                "the parse must refuse the reference, not wait for a socket");
    }
}

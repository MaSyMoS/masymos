package org.masymos.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnnotationUriResolverTest {

    private final AnnotationUriResolver resolver = new AnnotationUriResolver();

    @Test
    void keepsDirectUrlAsSingleCandidate() {
        var url = "https://www.ebi.ac.uk/chebi/searchId.do?chebiId=CHEBI:15377";

        var resolution = resolver.resolve(url);

        assertEquals(AnnotationResolution.Strategy.DIRECT_URL, resolution.strategy());
        assertEquals(1, resolution.candidateUrls().size());
        assertEquals(url, resolution.candidateUrls().getFirst());
        assertTrue(resolution.resolvable());
    }

    @Test
    void normalizesIdentifiersOrgPathForm() {
        var resolution = resolver.resolve("http://identifiers.org/pubmed/11429446");

        assertEquals(AnnotationResolution.Strategy.IDENTIFIERS_ORG_URL, resolution.strategy());
        assertEquals("pubmed", resolution.resourcePrefix());
        assertEquals("11429446", resolution.identifier());
        assertEquals("https://identifiers.org/pubmed:11429446", resolution.candidateUrls().getFirst());
        assertEquals(2, resolution.candidateUrls().size());
    }

    @Test
    void deduplicatesCanonicalIdentifiersOrgUrl() {
        var resolution = resolver.resolve("https://identifiers.org/pubmed:11429446");

        assertEquals(1, resolution.candidateUrls().size());
        assertEquals("https://identifiers.org/pubmed:11429446", resolution.candidateUrls().getFirst());
    }

    @Test
    void mapsMiriamUrnToIdentifiersOrg() {
        var resolution = resolver.resolve("urn:miriam:pubmed:11429446");

        assertEquals(AnnotationResolution.Strategy.MIRIAM_URN, resolution.strategy());
        assertEquals("pubmed", resolution.resourcePrefix());
        assertEquals("11429446", resolution.identifier());
        assertEquals("https://identifiers.org/pubmed:11429446", resolution.candidateUrls().getFirst());
    }

    @Test
    void rejectsIncompleteMiriamUrn() {
        assertFalse(resolver.resolve("urn:miriam:").resolvable());
        assertFalse(resolver.resolve("urn:miriam:pubmed").resolvable());
        assertFalse(resolver.resolve("urn:miriam::11429446").resolvable());
    }

    @Test
    void rejectsUnknownSchemesAndBlankInput() {
        assertFalse(resolver.resolve("ftp://example.org/model").resolvable());
        assertFalse(resolver.resolve("file:///C:/tmp/model.xml").resolvable());
        assertFalse(resolver.resolve("   ").resolvable());
        assertFalse(resolver.resolve(null).resolvable());
    }

    @Test
    void fallsBackToDirectUrlForUnparsableIdentifiersOrgPath() {
        var resolution = resolver.resolve("https://identifiers.org/");

        assertEquals(AnnotationResolution.Strategy.DIRECT_URL, resolution.strategy());
        assertEquals(1, resolution.candidateUrls().size());
    }
}

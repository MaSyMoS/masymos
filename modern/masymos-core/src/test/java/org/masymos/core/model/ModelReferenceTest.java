package org.masymos.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelReferenceTest {
    @Test
    void rejectsBlankFileId() {
        var uri = URI.create("https://example.org/model.xml");

        assertThrows(IllegalArgumentException.class, () -> new ModelReference(" ", uri, ModelType.SBML));
    }

    @Test
    void keepsRequiredModelMetadata() {
        var uri = URI.create("https://example.org/model.xml");
        var reference = new ModelReference("BIOMD0000000196", uri, ModelType.SBML);

        assertEquals("BIOMD0000000196", reference.fileId());
        assertEquals(uri, reference.sourceUri());
        assertEquals(ModelType.SBML, reference.modelType());
        assertNull(reference.versionId());
    }

    @Test
    void keepsVersionIdVerbatimWithoutOrderingOrParsingIt() {
        var uri = URI.create("https://example.org/model.xml");

        assertEquals("2014-01-01", new ModelReference("BIOMD1", uri, ModelType.SBML, "2014-01-01").versionId());
        assertEquals("b8f1c0d", new ModelReference("BIOMD1", uri, ModelType.SBML, "b8f1c0d").versionId());
        assertEquals("v3", new ModelReference("BIOMD1", uri, ModelType.SBML, " v3 ").versionId());
    }

    @Test
    void normalisesBlankVersionIdToAbsent() {
        var uri = URI.create("https://example.org/model.xml");

        assertNull(new ModelReference("BIOMD1", uri, ModelType.SBML, "  ").versionId());
        assertNull(new ModelReference("BIOMD1", uri, ModelType.SBML, "").versionId());
    }

    @Test
    void carriesTheDocumentMetadataAndDefaultsItToEmpty() {
        var uri = URI.create("https://example.org/model.xml");

        assertEquals(Map.of(), new ModelReference("BIOMD1", uri, ModelType.SBML).metadata());
        assertEquals(
                Map.of("submitter", "jj"),
                new ModelReference("BIOMD1", uri, ModelType.SBML, null, Map.of("submitter", "jj")).metadata());
    }

    @Test
    void rejectsANullMetadataValueInsteadOfFailingLater() {
        var uri = URI.create("https://example.org/model.xml");
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("submitter", null);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ModelReference("BIOMD1", uri, ModelType.SBML, null, metadata));
    }

    @Test
    void keepsTheMetadataImmutable() {
        var uri = URI.create("https://example.org/model.xml");
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("submitter", "jj");
        var reference = new ModelReference("BIOMD1", uri, ModelType.SBML, null, metadata);

        metadata.put("later", "mutation");

        assertEquals(Map.of("submitter", "jj"), reference.metadata());
        assertThrows(UnsupportedOperationException.class, () -> reference.metadata().put("x", "y"));
    }
}

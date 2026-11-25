package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataJsonTest {
    @Test
    void roundTripsAMetadataMap() {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("submitter", "Jane \"JJ\" Doe");
        metadata.put("release", "2014-01-01");
        metadata.put("note", "Grüße");

        assertEquals(metadata, MetadataJson.parse(MetadataJson.write(metadata)));
    }

    @Test
    void writesNoPropertyForAbsentMetadata() {
        assertNull(MetadataJson.write(null));
        assertNull(MetadataJson.write(Map.of()));
    }

    @Test
    void readsAbsentMetadataAsAnEmptyMap() {
        assertEquals(Map.of(), MetadataJson.parse(null));
        assertEquals(Map.of(), MetadataJson.parse("  "));
    }

    @Test
    void rejectsCorruptStoredMetadata() {
        assertThrows(IllegalArgumentException.class, () -> MetadataJson.parse("{not json"));
    }
}

package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FieldCatalogTest {
    @Test
    void everyFieldHasAtLeastOneEntity() {
        assertTrue(Arrays.stream(QueryField.values()).allMatch(field -> !FieldCatalog.entitiesOf(field).isEmpty()));
    }

    @Test
    void everyEntityHasAtLeastOneField() {
        assertTrue(Arrays.stream(QueryEntity.values()).allMatch(entity -> !FieldCatalog.fieldsFor(entity).isEmpty()));
    }

    @Test
    void theAnnotationOnlyFieldsAreAnnotationOnly() {
        assertEquals(Set.of(QueryEntity.ANNOTATION), FieldCatalog.entitiesOf(QueryField.RESOURCE_TEXT));
        assertEquals(Set.of(QueryEntity.ANNOTATION), FieldCatalog.entitiesOf(QueryField.NON_RDF));
    }

    @Test
    void theSbmlLevelIsAModelFieldOnly() {
        assertEquals(Set.of(QueryEntity.MODEL), FieldCatalog.entitiesOf(QueryField.SBML_LEVEL));
        assertFalse(FieldCatalog.fieldsFor(QueryEntity.SEDML).contains(QueryField.SBML_LEVEL));
    }

    @Test
    void theSedmlOnlyFieldsAreSedmlOnly() {
        for (QueryField field : Set.of(
                QueryField.OUTPUT_TYPE, QueryField.SIM_TYPE, QueryField.DATA_LABEL, QueryField.MATH)) {
            assertEquals(Set.of(QueryEntity.SEDML), FieldCatalog.entitiesOf(field), field.name());
            assertFalse(FieldCatalog.fieldsFor(QueryEntity.MODEL).contains(field), field.name());
        }
    }

    @Test
    void theArchiveFieldsAreArchiveOnly() {
        assertEquals(Set.of(QueryEntity.ARCHIVE), FieldCatalog.entitiesOf(QueryField.ARCHIVE_NAME));
        assertEquals(Set.of(QueryEntity.ARCHIVE), FieldCatalog.entitiesOf(QueryField.ARCHIVE_URI));
    }

    @Test
    void theKeywordBelongsToEveryEntity() {
        assertEquals(EnumSet.allOf(QueryEntity.class), FieldCatalog.entitiesOf(QueryField.KEYWORD));
        for (QueryEntity entity : QueryEntity.values()) {
            assertTrue(FieldCatalog.fieldsFor(entity).contains(QueryField.KEYWORD), entity.name());
        }
    }

    @Test
    void theReturnedSetsAreImmutable() {
        assertThrows(
                UnsupportedOperationException.class, () -> FieldCatalog.fieldsFor(QueryEntity.MODEL).clear());
        assertThrows(
                UnsupportedOperationException.class, () -> FieldCatalog.entitiesOf(QueryField.KEYWORD).clear());
    }

    @Test
    void theWireNameRoundTrips() {
        for (QueryEntity entity : QueryEntity.values()) {
            assertEquals(entity, QueryEntity.fromWire(entity.wire()));
            assertEquals(entity.name().toLowerCase(java.util.Locale.ROOT), entity.wire());
        }
        assertEquals(QueryEntity.ARCHIVE, QueryEntity.fromWire("archive"));
    }

    @Test
    void anUnknownWireNameNamesTheKnownEntities() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> QueryEntity.fromWire("nope"));

        String message = thrown.getMessage();
        assertTrue(message.contains("unknown query entity 'nope'"), message);
        for (QueryEntity entity : QueryEntity.values()) {
            assertTrue(message.contains(entity.wire()), message);
        }
    }
}

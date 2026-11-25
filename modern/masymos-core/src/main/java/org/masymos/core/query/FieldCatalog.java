package org.masymos.core.query;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which {@link QueryField}s are meaningful for which {@link QueryEntity}, backed by one static
 * table. The catalog is <em>discovery only</em>: it tells a client what a field means for an
 * entity, and nothing in the query path consults it, so no existing request starts failing. Every
 * field it knows has at least one entity and every entity has at least one field; a test pins both.
 */
public final class FieldCatalog {
    private static final Map<QueryEntity, Set<QueryField>> FIELDS_BY_ENTITY = buildFieldsByEntity();

    private static final Map<QueryField, Set<QueryEntity>> ENTITIES_BY_FIELD = invert(FIELDS_BY_ENTITY);

    private FieldCatalog() {}

    /** An immutable, non-empty set of the fields meaningful for {@code entity}. */
    public static Set<QueryField> fieldsFor(QueryEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("query entity must not be null");
        }
        return FIELDS_BY_ENTITY.get(entity);
    }

    /** An immutable, non-empty set of the entities that can be searched on {@code field}. */
    public static Set<QueryEntity> entitiesOf(QueryField field) {
        if (field == null) {
            throw new IllegalArgumentException("query field must not be null");
        }
        return ENTITIES_BY_FIELD.get(field);
    }

    private static Map<QueryEntity, Set<QueryField>> buildFieldsByEntity() {
        Map<QueryEntity, Set<QueryField>> table = new EnumMap<>(QueryEntity.class);
        table.put(QueryEntity.MODEL, setOf(
                QueryField.ID,
                QueryField.NAME,
                QueryField.URI,
                QueryField.COMPONENT,
                QueryField.VARIABLE,
                QueryField.COMPARTMENT,
                QueryField.SPECIES,
                QueryField.REACTION,
                QueryField.PARAMETER,
                QueryField.CREATOR,
                QueryField.AUTHOR,
                QueryField.TITLE,
                QueryField.ABSTRACT,
                QueryField.AFFILIATION,
                QueryField.JOURNAL,
                QueryField.YEAR,
                QueryField.FAMILYNAME,
                QueryField.GIVENNAME,
                QueryField.EMAIL,
                QueryField.ORGANIZATION,
                QueryField.MODEL_SOURCE,
                QueryField.SBML_LEVEL,
                QueryField.SBML_VERSION,
                QueryField.CELLML_VERSION,
                QueryField.SCHEMA_VERSION,
                QueryField.KEYWORD));
        // No SBML_LEVEL/SBML_VERSION/CELLML_VERSION here: the index carries them, but no SED-ML
        // node ever has a value for them.
        table.put(QueryEntity.SEDML, setOf(
                QueryField.OUTPUT_TYPE,
                QueryField.DATA_LABEL,
                QueryField.SIM_KISAO,
                QueryField.SIM_TYPE,
                QueryField.MATH,
                QueryField.NAME,
                QueryField.ID,
                QueryField.URI,
                QueryField.SCHEMA_VERSION,
                QueryField.KEYWORD));
        table.put(QueryEntity.ANNOTATION, setOf(
                QueryField.RESOURCE_URI,
                QueryField.RESOURCE_TEXT,
                QueryField.CREATOR,
                QueryField.AUTHOR,
                QueryField.NON_RDF,
                QueryField.KEYWORD));
        table.put(QueryEntity.PERSON, setOf(
                QueryField.FAMILYNAME,
                QueryField.GIVENNAME,
                QueryField.EMAIL,
                QueryField.ORGANIZATION,
                QueryField.KEYWORD));
        table.put(QueryEntity.PUBLICATION, setOf(
                QueryField.TITLE,
                QueryField.JOURNAL,
                QueryField.AFFILIATION,
                QueryField.YEAR,
                QueryField.AUTHOR,
                QueryField.CREATOR,
                QueryField.KEYWORD));
        table.put(QueryEntity.ARCHIVE, setOf(
                QueryField.ARCHIVE_NAME,
                QueryField.ARCHIVE_URI,
                QueryField.KEYWORD));

        Map<QueryEntity, Set<QueryField>> immutable = new EnumMap<>(QueryEntity.class);
        table.forEach((entity, fields) -> immutable.put(entity, Collections.unmodifiableSet(fields)));
        return Collections.unmodifiableMap(immutable);
    }

    private static Map<QueryField, Set<QueryEntity>> invert(Map<QueryEntity, Set<QueryField>> table) {
        Map<QueryField, Set<QueryEntity>> inverted = new EnumMap<>(QueryField.class);
        table.forEach((entity, fields) -> fields.forEach(
                field -> inverted.computeIfAbsent(field, ignored -> EnumSet.noneOf(QueryEntity.class))
                        .add(entity)));
        Map<QueryField, Set<QueryEntity>> immutable = new EnumMap<>(QueryField.class);
        inverted.forEach((field, entities) -> immutable.put(field, Collections.unmodifiableSet(entities)));
        return Collections.unmodifiableMap(immutable);
    }

    private static Set<QueryField> setOf(QueryField first, QueryField... rest) {
        Set<QueryField> fields = EnumSet.of(first, rest);
        return fields;
    }
}

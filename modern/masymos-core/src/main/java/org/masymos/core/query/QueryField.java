package org.masymos.core.query;

/**
 * What a query clause can be restricted to. {@code COMPONENT}, {@code VARIABLE}, {@code COMPARTMENT},
 * {@code SPECIES}, {@code REACTION} and {@code PARAMETER} name constituent properties, but they are
 * fields of {@link QueryEntity#MODEL}: the model full-text index carries them flattened, so a clause on
 * one scores at model level and returns model rows. There is no constituent entity, no constituent
 * index and no constituent result shape (decision D3, QRY-6).
 */
public enum QueryField {
    ID,
    NAME,
    URI,
    COMPONENT,
    VARIABLE,
    COMPARTMENT,
    SPECIES,
    REACTION,
    PARAMETER,
    CREATOR,
    AUTHOR,
    TITLE,
    ABSTRACT,
    AFFILIATION,
    JOURNAL,
    YEAR,
    FAMILYNAME,
    GIVENNAME,
    EMAIL,
    ORGANIZATION,
    MODEL_SOURCE,
    OUTPUT_TYPE,
    DATA_LABEL,
    SIM_KISAO,
    SIM_TYPE,
    MATH,
    RESOURCE_URI,
    RESOURCE_TEXT,
    NON_RDF,
    SBML_LEVEL,
    SBML_VERSION,
    CELLML_VERSION,
    SCHEMA_VERSION,
    KEYWORD,
    ARCHIVE_NAME,
    ARCHIVE_URI
}

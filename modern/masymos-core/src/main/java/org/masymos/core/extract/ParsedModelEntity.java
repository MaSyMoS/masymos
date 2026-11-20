package org.masymos.core.extract;

import java.util.Map;

public record ParsedModelEntity(
        EntityType type,
        String id,
        String name,
        Map<String, String> properties
) {
    public enum EntityType {
        COMPARTMENT,
        SPECIES,
        REACTION,
        PARAMETER,
        RULE,
        FUNCTION,
        EVENT,
        COMPONENT,
        VARIABLE,
        CELLML_REACTION,
        /**
         * One {@code <import>} element of a CellML model. A distinct type - not a property on
         * {@link #COMPONENT} - because entities are keyed by {@code type:id} and an import must never
         * merge with the local component of the same name.
         */
        CELLML_IMPORT,
        /** One {@code <importedComponent>}; variables are attached only when a resolver resolved it. */
        CELLML_IMPORTED_COMPONENT,
        SEDML_MODELREFERENCE,
        SEDML_SIMULATION,
        SEDML_TASK,
        SEDML_DATAGENERATOR,
        SEDML_VARIABLE,
        SEDML_OUTPUT,
        SEDML_CURVE,
        SEDML_SURFACE,
        SEDML_DATASET,
        /**
         * One {@code <uniformRange>}, {@code <vectorRange>} or {@code <functionalRange>} of a
         * repeated task. A distinct type because {@code range} attributes on other elements refer to
         * it by id, and an entity is keyed by {@code type:id}.
         */
        SEDML_RANGE,
        /**
         * One {@code <setValue>} (or attribute change) inside a repeated task's
         * {@code <listOfChanges>}. Changes have no id in SED-ML, so one is generated from their
         * position; a distinct type keeps them from merging with any other entity carrying that id.
         */
        SEDML_CHANGE,
        /**
         * One unit a model declares or uses, keyed by its canonical key. The only entity type that
         * becomes a shared node: the writer merges it on {@code key} instead of creating it per
         * document (design 4.2).
         */
        UNIT
    }
}

package org.masymos.core.extract;

import java.util.List;
import java.util.Map;
import org.masymos.core.model.ModelReference;

public record ParsedModel(
        ModelReference reference,
        String modelId,
        String modelName,
        Map<String, String> properties,
        List<ParsedModelEntity> entities,
        List<ParsedReactionParticipant> reactionParticipants,
        List<ParsedAnnotation> annotations,
        List<ParsedPublication> publications,
        List<ParsedEntityRelationship> entityRelationships
) {
    public ParsedModel {
        entities = entities == null ? List.of() : List.copyOf(entities);
        reactionParticipants = reactionParticipants == null ? List.of() : List.copyOf(reactionParticipants);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        publications = publications == null ? List.of() : List.copyOf(publications);
        entityRelationships = entityRelationships == null ? List.of() : List.copyOf(entityRelationships);
    }

    public ParsedModel(ModelReference reference, String modelId, String modelName, Map<String, String> properties) {
        this(reference, modelId, modelName, properties, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public ParsedModel(
            ModelReference reference,
            String modelId,
            String modelName,
            Map<String, String> properties,
            List<ParsedModelEntity> entities,
            List<ParsedReactionParticipant> reactionParticipants
    ) {
        this(reference, modelId, modelName, properties, entities, reactionParticipants, List.of(), List.of(), List.of());
    }

    public ParsedModel(
            ModelReference reference,
            String modelId,
            String modelName,
            Map<String, String> properties,
            List<ParsedModelEntity> entities,
            List<ParsedReactionParticipant> reactionParticipants,
            List<ParsedAnnotation> annotations
    ) {
        this(reference, modelId, modelName, properties, entities, reactionParticipants, annotations, List.of(), List.of());
    }

    public ParsedModel(
            ModelReference reference,
            String modelId,
            String modelName,
            Map<String, String> properties,
            List<ParsedModelEntity> entities,
            List<ParsedReactionParticipant> reactionParticipants,
            List<ParsedAnnotation> annotations,
            List<ParsedPublication> publications
    ) {
        this(reference, modelId, modelName, properties, entities, reactionParticipants, annotations, publications, List.of());
    }
}

package org.masymos.core.extract;

import java.util.Map;

public record ParsedReactionParticipant(
        String reactionId,
        String speciesId,
        Role role,
        Map<String, String> properties
) {
    public ParsedReactionParticipant(String reactionId, String speciesId, Role role) {
        this(reactionId, speciesId, role, Map.of());
    }

    public ParsedReactionParticipant {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public enum Role {
        REACTANT,
        PRODUCT,
        MODIFIER
    }
}

package org.masymos.core.extract;

import java.util.Map;

public record ParsedPerson(
        String givenName,
        String familyName,
        String email,
        String organization
) {
    public Map<String, String> properties() {
        return Map.of(
                "givenName", givenName,
                "familyName", familyName,
                "email", email,
                "organization", organization
        );
    }
}

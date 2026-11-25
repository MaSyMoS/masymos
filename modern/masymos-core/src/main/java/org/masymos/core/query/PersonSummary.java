package org.masymos.core.query;

public record PersonSummary(
        String givenName,
        String familyName,
        String email,
        String organization
) {
}

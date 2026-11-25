package org.masymos.service.api;

import java.util.List;
import java.util.Objects;

/**
 * The run asked for ontology term providers and none is usable (task T-017, finding GAP02-001).
 *
 * <p>It is an advice-owned type rather than a returned {@code ProblemDetail}: {@code
 * ApiExceptionHandler} is the one place that produces a problem detail and its {@code type} URIs, so a
 * refusal cannot answer with a second shape. The advice answers {@code 409 provider-unavailable} and
 * copies {@link #missingVariables()} into the body, which is what the documentation promises.
 */
public class ProviderUnavailableException extends RuntimeException {
    private final List<String> missingVariables;

    public ProviderUnavailableException(String message, List<String> missingVariables) {
        super(message);
        this.missingVariables = List.copyOf(Objects.requireNonNull(missingVariables, "missingVariables"));
    }

    /** The environment variables that would make the refused mode honourable. */
    public List<String> missingVariables() {
        return missingVariables;
    }
}

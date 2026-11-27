package org.masymos.service.api;

/**
 * A request refused by {@code SharedSecretFilter} because it carried no valid {@code X-MaSyMoS-Token}
 * header (design decision 48, finding {@code C04-3}).
 *
 * <p>The filter raises it before a handler is chosen, so it is not a controller's exception: it exists
 * so the refusal can be resolved through {@link ApiExceptionHandler} and rendered as the documented
 * {@code 401 unauthenticated} problem detail instead of a body written by the filter. The message names
 * the header and never the value that arrived.
 */
public class UnauthenticatedRequestException extends RuntimeException {
    public UnauthenticatedRequestException(String message) {
        super(message);
    }
}

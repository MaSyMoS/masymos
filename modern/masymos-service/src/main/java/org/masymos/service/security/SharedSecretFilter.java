package org.masymos.service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.masymos.service.api.UnauthenticatedRequestException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The opt-in shared-secret gate in front of every endpoint (design decision 48, finding {@code C04-3}).
 *
 * <p>When {@code masymos.security.shared-secret} is set, a request without a matching {@value #HEADER}
 * header is refused before any handler is chosen. The refusal is rendered through
 * {@code ApiExceptionHandler} - the one producer of an error response - rather than written by this
 * filter, which is why the {@code handlerExceptionResolver} is injected: a second place that writes a
 * problem body is how the documented error shape becomes two shapes.
 *
 * <p>The comparison is constant-time ({@link MessageDigest#isEqual}) so a wrong guess cannot be
 * narrowed by timing. The received value is never echoed, logged or stored: the refusal message names
 * the header, not what arrived in it.
 *
 * <p>One rule, no per-path exemption list: when the secret is not configured the filter is not on the
 * request path at all ({@link #shouldNotFilter}), so the default deployment behaves exactly as before.
 * The application refuses to start with a secret that is blank or shorter than {@link
 * SharedSecretProperties#MINIMUM_LENGTH} instead of running with a control weaker than documented -
 * "off" is the empty value only, never a value that merely looks unset.
 *
 * <p>The bean exists only in a servlet application ({@code @ConditionalOnWebApplication}), because its
 * refusal is rendered through the MVC exception resolver: a context without web MVC
 * ({@code spring.main.web-application-type=none}, {@code DisabledStorageConfigurationTest}) has no such
 * resolver, and a filter that cannot render its own refusal has no business being registered.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class SharedSecretFilter extends OncePerRequestFilter {
    /** The header carrying the shared secret; also what the OpenAPI document declares. */
    public static final String HEADER = "X-MaSyMoS-Token";

    private static final String PROPERTY = "masymos.security.shared-secret";

    private final SharedSecretProperties properties;

    private final HandlerExceptionResolver resolver;

    private final byte[] expected;

    public SharedSecretFilter(
            SharedSecretProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver
    ) {
        this.properties = properties;
        this.resolver = resolver;
        if (properties.enabled()) {
            var secret = properties.sharedSecret();
            // The length, never the value: a startup failure ends up in a log or a bug report.
            if (secret.isBlank() || secret.length() < SharedSecretProperties.MINIMUM_LENGTH) {
                throw new IllegalStateException(PROPERTY
                        + " must not be blank and must be at least " + SharedSecretProperties.MINIMUM_LENGTH
                        + " characters; the configured value has " + secret.length()
                        + " characters. A value that is blank or guessable is not a control, so the service"
                        + " refuses to start instead of running unprotected.");
            }
            this.expected = secret.getBytes(StandardCharsets.UTF_8);
        } else {
            this.expected = null;
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        var presented = request.getHeader(HEADER);
        if (presented == null
                || !MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            var rendered = resolver.resolveException(
                    request,
                    response,
                    null,
                    new UnauthenticatedRequestException("the request carries no valid " + HEADER + " header"));
            if (rendered == null && !response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            }
            return;
        }
        chain.doFilter(request, response);
    }
}

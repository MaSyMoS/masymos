package org.masymos.service.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one credential the service can require (design decision 48, finding {@code C04-3}).
 *
 * <p>It is <strong>off unless an operator sets it</strong>. The default deployment binds to loopback
 * and a local service needs no credential; requiring one by default would break every existing caller
 * to buy nothing. A shared secret is a bearer credential - whoever reads the header can replay it - so
 * it is only meaningful behind TLS or on loopback, and this property does not by itself make an
 * exposed instance safe.
 *
 * <p>{@link #MINIMUM_LENGTH} is 16 characters because a shorter value is guessable: an eight-character
 * secret is a few hours of an offline search and a few minutes of an unthrottled one, and a filter that
 * accepts it runs with a control that only looks like one. A configured value below the floor is
 * therefore refused at startup by {@code SharedSecretFilter} with {@link IllegalStateException}, not
 * silently downgraded.
 *
 * <p>{@code null} or the <em>empty</em> string means the filter is off - that is the shape
 * {@code application.yml} produces when the environment variable is unset. A value that is neither,
 * but is blank (whitespace only), is <strong>not</strong> "off": it is a misconfiguration that looks
 * like protection, so it is refused at startup like a too-short one. Treating it as off would answer
 * every request `200` while the operator believes the surface is guarded.
 *
 * @param sharedSecret the value every request must carry in {@code X-MaSyMoS-Token}; {@code null} or the
 *     empty string leaves the filter off, and a blank or too-short value fails startup
 */
@ConfigurationProperties(prefix = "masymos.security")
public record SharedSecretProperties(String sharedSecret) {
    /** Below this many characters a secret is guessable rather than a control. */
    public static final int MINIMUM_LENGTH = 16;

    /**
     * Whether the filter is on. Only {@code null} and the empty string mean "off": an all-whitespace
     * value is on, and therefore fails startup in {@code SharedSecretFilter}, because "silently off"
     * and "silently unprotected" must not be the same configuration.
     */
    public boolean enabled() {
        return sharedSecret != null && !sharedSecret.isEmpty();
    }

    public static SharedSecretProperties disabled() {
        return new SharedSecretProperties(null);
    }
}

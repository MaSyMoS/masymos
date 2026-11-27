package org.masymos.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.service.api.UnauthenticatedRequestException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * The filter's behaviour on its own, without a Spring context (design decision 48, finding {@code
 * C04-3}). Every case calls {@code doFilter} so {@code shouldNotFilter} is exercised too, and every case
 * names what it proves.
 *
 * <p>"The chain was not called" is read from the chain's request being unset - {@code MockFilterChain}
 * forwards whatever it was given, and these tests always give it the caller's request.
 */
class SharedSecretFilterTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void aRequestWithoutTheHeaderIsRefusedThroughTheResolverAndNeverReachesTheChain() throws Exception {
        var resolver = new CapturingResolver();
        var filter = new SharedSecretFilter(new SharedSecretProperties(SECRET), resolver);
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "the chain must not be called for a refused request");
        assertEquals(1, resolver.exceptions.size(), "the resolver must render the refusal");
        var raised = resolver.exceptions.get(0);
        assertSame(UnauthenticatedRequestException.class, raised.getClass());
        assertTrue(raised.getMessage().contains(SharedSecretFilter.HEADER),
                "the refusal must name the header: " + raised.getMessage());
    }

    @Test
    void aWrongSecretIsRefusedLikeAMissingOne() throws Exception {
        var resolver = new CapturingResolver();
        var filter = new SharedSecretFilter(new SharedSecretProperties(SECRET), resolver);
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(SharedSecretFilter.HEADER, "fedcba9876543210fedcba9876543210");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest(), "the chain must not be called for a wrong secret");
        assertEquals(1, resolver.exceptions.size());
        assertSame(UnauthenticatedRequestException.class, resolver.exceptions.get(0).getClass());
        assertTrue(resolver.exceptions.get(0).getMessage().contains(SharedSecretFilter.HEADER));
    }

    @Test
    void aWrongSecretOfADifferentLengthIsRefusedWithoutAFailedComparison() throws Exception {
        var resolver = new CapturingResolver();
        var filter = new SharedSecretFilter(new SharedSecretProperties(SECRET), resolver);
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(SharedSecretFilter.HEADER, "short");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(chain.getRequest(), "a shorter guess is a wrong guess, not an error");
        assertEquals(1, resolver.exceptions.size());
        assertSame(UnauthenticatedRequestException.class, resolver.exceptions.get(0).getClass());
    }

    @Test
    void theRightSecretReachesTheChain() throws Exception {
        var resolver = new CapturingResolver();
        var filter = new SharedSecretFilter(new SharedSecretProperties(SECRET), resolver);
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.addHeader(SharedSecretFilter.HEADER, SECRET);
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest(), "the right secret must reach the chain");
        assertTrue(resolver.exceptions.isEmpty(), "an accepted request raises nothing");
    }

    @Test
    void withNoSecretConfiguredTheFilterIsInert() throws Exception {
        var resolver = new CapturingResolver();
        var filter = new SharedSecretFilter(SharedSecretProperties.disabled(), resolver);
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        var chain = new MockFilterChain();

        assertTrue(filter.shouldNotFilter(request),
                "an unconfigured filter must stay out of the request path");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest(), "without a secret the request passes untouched");
        assertTrue(resolver.exceptions.isEmpty(), "nothing is refused when no secret is configured");
    }

    @Test
    void aSecretShorterThanTheMinimumIsRefusedAtConstruction() {
        var tooShort = "0123456789abc";

        var failure = assertThrows(
                IllegalStateException.class,
                () -> new SharedSecretFilter(new SharedSecretProperties(tooShort), new CapturingResolver()));

        assertTrue(failure.getMessage().contains("masymos.security.shared-secret"),
                "the failure must name the property: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(String.valueOf(SharedSecretProperties.MINIMUM_LENGTH)),
                "the failure must name the minimum: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(String.valueOf(tooShort.length())),
                "the failure must state the configured length: " + failure.getMessage());
        assertFalse(failure.getMessage().contains(tooShort),
                "the failure must never contain the secret: " + failure.getMessage());
    }

    /**
     * A whitespace-only value is a long "secret" that is not off and must not pass as protection: the
     * review of this change found that {@code isBlank()} read it as "no secret configured", so an
     * operator who pasted spaces got a service that answered everything `200` while the document
     * claimed nothing either way. Only {@code null} or the empty string mean off.
     */
    @Test
    void aWhitespaceOnlySecretIsRefusedAtStartupRatherThanReadAsOff() {
        var spaces = "                    ";
        assertTrue(new SharedSecretProperties(spaces).enabled(),
                "blank is not off: only null and the empty string are");

        var failure = assertThrows(
                IllegalStateException.class,
                () -> new SharedSecretFilter(new SharedSecretProperties(spaces), new CapturingResolver()));

        assertTrue(failure.getMessage().contains("masymos.security.shared-secret"),
                "the failure must name the property: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("blank"),
                "the failure must say what is wrong with it: " + failure.getMessage());
        assertFalse(failure.getMessage().contains(spaces),
                "the failure must not reproduce the value: " + failure.getMessage());
    }

    @Test
    void anEmptySecretLeavesTheFilterOff() throws Exception {
        var resolver = new CapturingResolver();
        var filter = new SharedSecretFilter(new SharedSecretProperties(""), resolver);
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest(), "the empty value is the documented off switch");
        assertTrue(resolver.exceptions.isEmpty());
    }

    /**
     * The one branch that can turn a refusal into a `200`: a resolver that acts on nothing. The fallback
     * is what keeps the status honest, so it is pinned rather than trusted - the documented
     * {@code application/problem+json} body is the resolver's job, the `401` is the filter's.
     */
    @Test
    void aResolverThatRendersNothingStillLeavesA401() throws Exception {
        HandlerExceptionResolver rendersNothing = (request, response, handler, raised) -> null;
        var filter = new SharedSecretFilter(new SharedSecretProperties(SECRET), rendersNothing);
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "a refusal never reaches the chain");
        assertEquals(401, response.getStatus(), "a fallback with no body is still a refusal");
    }

    @Test
    void anAlreadyCommittedResponseIsLeftAlone() throws Exception {
        HandlerExceptionResolver commitsItsOwnAnswer = (request, response, handler, raised) -> {
            var mock = (MockHttpServletResponse) response;
            mock.setStatus(401);
            mock.setCommitted(true);
            return null;
        };
        var filter = new SharedSecretFilter(new SharedSecretProperties(SECRET), commitsItsOwnAnswer);
        var request = new MockHttpServletRequest("GET", "/api/v1/health");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertTrue(response.isCommitted(), "the resolver's own answer was committed");
        assertEquals(401, response.getStatus(), "a committed response must not be overwritten");
    }

    /** Records what the filter asked it to render, and returns a view so the filter does not fall back. */
    private static final class CapturingResolver implements HandlerExceptionResolver {
        private final List<Exception> exceptions = new ArrayList<>();

        @Override
        public ModelAndView resolveException(
                HttpServletRequest request,
                HttpServletResponse response,
                Object handler,
                Exception exception
        ) {
            exceptions.add(exception);
            return new ModelAndView();
        }
    }
}

package org.masymos.core.annotation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, network-free resolution of annotation URIs into candidate fetch URLs.
 *
 * <p>The legacy implementation asked the MIRIAM web service for every URI. This resolver reproduces
 * the same effective outcome for the URI shapes found in model annotations without any remote call:
 * direct HTTP(S) URLs stay as they are, identifiers.org URLs are normalized to the canonical
 * {@code https://identifiers.org/<prefix>:<id>} form, and MIRIAM URNs are mapped to that same form.
 * Unknown shapes resolve to no candidate at all instead of guessing.
 */
public final class AnnotationUriResolver {

    public static final String IDENTIFIERS_ORG_RESOLVER = "https://identifiers.org/";

    private static final String MIRIAM_URN_PREFIX = "urn:miriam:";
    private static final String IDENTIFIERS_ORG_HOST = "identifiers.org";

    public AnnotationResolution resolve(String uri) {
        if (uri == null || uri.isBlank()) {
            return AnnotationResolution.unresolved(uri);
        }

        var candidate = uri.trim();
        if (candidate.toLowerCase(Locale.ROOT).startsWith(MIRIAM_URN_PREFIX)) {
            return resolveMiriamUrn(candidate);
        }
        if (isHttpUri(candidate)) {
            return resolveWebUri(candidate);
        }
        return AnnotationResolution.unresolved(candidate);
    }

    private AnnotationResolution resolveMiriamUrn(String urn) {
        var body = urn.substring(MIRIAM_URN_PREFIX.length());
        return colonPair(body)
                .map(pair -> resolved(urn, pair[0], pair[1], AnnotationResolution.Strategy.MIRIAM_URN))
                .orElseGet(() -> AnnotationResolution.unresolved(urn));
    }

    private AnnotationResolution resolveWebUri(String url) {
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException exception) {
            return AnnotationResolution.unresolved(url);
        }

        var host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals(IDENTIFIERS_ORG_HOST) && !host.equals("www." + IDENTIFIERS_ORG_HOST)) {
            return new AnnotationResolution(url, null, null, AnnotationResolution.Strategy.DIRECT_URL, List.of(url));
        }

        var prefixAndIdentifier = fromIdentifiersOrgPath(parsed.getPath());
        if (prefixAndIdentifier == null) {
            return new AnnotationResolution(url, null, null, AnnotationResolution.Strategy.DIRECT_URL, List.of(url));
        }
        var resolution = resolved(
                url,
                prefixAndIdentifier[0],
                prefixAndIdentifier[1],
                AnnotationResolution.Strategy.IDENTIFIERS_ORG_URL
        );
        var withOriginal = new LinkedHashSet<>(resolution.candidateUrls());
        withOriginal.add(url);
        return new AnnotationResolution(
                url,
                resolution.resourcePrefix(),
                resolution.identifier(),
                resolution.strategy(),
                List.copyOf(withOriginal)
        );
    }

    private static AnnotationResolution resolved(String originalUri, String prefix, String identifier, AnnotationResolution.Strategy strategy) {
        return new AnnotationResolution(
                originalUri,
                prefix,
                identifier,
                strategy,
                List.of(IDENTIFIERS_ORG_RESOLVER + prefix + ":" + identifier)
        );
    }

    /**
     * Accepts both legacy identifiers.org path shapes: {@code /pubmed/11429446} and
     * {@code /pubmed:11429446}.
     */
    private static String[] fromIdentifiersOrgPath(String path) {
        if (path == null || path.isBlank() || path.equals("/")) {
            return null;
        }
        var withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        var trimmed = withoutLeadingSlash.endsWith("/")
                ? withoutLeadingSlash.substring(0, withoutLeadingSlash.length() - 1)
                : withoutLeadingSlash;
        if (trimmed.isBlank()) {
            return null;
        }

        var colonIndex = trimmed.indexOf(':');
        if (colonIndex > 0 && colonIndex < trimmed.length() - 1) {
            return new String[] {
                trimmed.substring(0, colonIndex), trimmed.substring(colonIndex + 1)
            };
        }

        var slashIndex = trimmed.indexOf('/');
        if (slashIndex > 0 && slashIndex < trimmed.length() - 1) {
            var prefix = trimmed.substring(0, slashIndex);
            var identifier = trimmed.substring(slashIndex + 1);
            return prefix.isBlank() || identifier.isBlank() ? null : new String[] {prefix, identifier};
        }

        return null;
    }

    private static java.util.Optional<String[]> colonPair(String body) {
        var colonIndex = body.indexOf(':');
        if (colonIndex <= 0 || colonIndex == body.length() - 1) {
            return java.util.Optional.empty();
        }
        var prefix = body.substring(0, colonIndex).trim();
        var identifier = body.substring(colonIndex + 1).trim();
        if (prefix.isBlank() || identifier.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new String[] {prefix, identifier});
    }

    private static boolean isHttpUri(String candidate) {
        var lower = candidate.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}

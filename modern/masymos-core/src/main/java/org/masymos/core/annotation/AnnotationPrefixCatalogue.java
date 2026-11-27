package org.masymos.core.annotation;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The static prefix catalogue: which prefixes are ontological, how their aliases and identifier
 * forms are normalised, and where each ontology is served.
 *
 * <p>Pure and I/O-free. The {@code ontological} flag is the single decision point of the term
 * stage: {@code false} means no term stage, no cache entry and no provider request, ever.
 *
 * <p>The five ontological prefixes are those that were both frequent and ontological in the
 * 2026-11-13 census. All five are served by OLS4; a capture of 2026-11-13
 * ({@code ols4-v2-classes-kisao-0000019.json}, {@code KISAO_0000019} answered with HTTP 200) shows that
 * OLS4 serves KISAO, so its OLS4 ontology id is declared like every other one.
 *
 * <p>One class-IRI column serves both providers, because they were measured on 2026-11-13 and agree
 * on all five prefixes: SBO is served from {@code biomodels.net} and FMA from {@code purl.org/sig},
 * not from the OBO purl, and both providers answer those forms (HTTP 200) while the purl form answers
 * 404 on both. The ontology ids stay per provider, because those do differ ({@code GO} against
 * {@code go}). Every value here is a measurement against a real response, not an inference.
 *
 * <p>A second column, the compact identifier, is what the identifiers.org resolver and page accept.
 * Measured on 2026-11-16 against {@code registry.api.identifiers.org} and
 * {@code resolver.api.identifiers.org}: four of the five namespaces set {@code namespaceEmbeddedInLui}
 * ({@code go}, {@code chebi}, {@code fma}, {@code sbo}), so their local id carries the prefix token and
 * {@code go:0008150} is rejected with HTTP 400 while {@code GO:0008150} answers 200; {@code biomodels.kisao}
 * does not, so its local id is {@code KISAO_0000019}. The page the fallback fetches is that same
 * compact identifier, which is why the URL is not {@code <prefix>:<id>} for any row.
 *
 * <p>{@code pageYieldsText} records whether that page is actually readable: only the ChEBI page is
 * server-rendered. GO refuses a plain client (403), the OLS4 FMA and KiSAO pages are single-page-app
 * shells, and SBO lands on a deprecation notice for one <em>resource</em>. Asking for those pages
 * produced a request that could only fail, which is why the flag exists.
 */
public final class AnnotationPrefixCatalogue {

    /**
     * The resolution strategy a cached negative was produced under; part of {@link #lookupFingerprint}.
     *
     * <p>Bump this when the way a lookup is performed changes — not when a term changes. A cached
     * {@code DEFINITIVE} "no such class" is only as good as the question that produced it, and the
     * question includes the strategy: on 2026-11-14 the OLS4 lookup moved from one class endpoint to a
     * type lookup followed by a class-or-property detail read, which turned a cached 404 for
     * {@code KISAO_0000245} (an object property, not a class) into a wrong answer that no template
     * change would have caught. Bumping this string is what retires those entries.
     *
     * <p>Bumped to {@code v3-compact-ids} on 2026-11-16 for the page question specifically: the page
     * fallback used to fetch {@code <canonicalPrefix>:<id>}, a URL the identifiers.org resolver rejects
     * with HTTP 400 for four of the five namespaces, and a page that yields no entity text (GO, FMA,
     * SBO, KiSAO) is no longer fetched at all but recorded as
     * {@code no page text for this prefix}. A {@code DEFINITIVE} negative produced under the old page
     * question was answering a different question, so it must not stay fresh: this bump retires it.
     *
     * <p>Bumped to {@code v4-merged-ids} on 2026-11-16 for the provider question: the term stage now
     * follows a merged id's {@code IAO_0100001} successor and indexes the successor's published text
     * under the cited id. A {@code DEFINITIVE} negative produced before that change was asked "is there
     * an entity at exactly this IRI", which is no longer the question — the lookup now asks "is there an
     * entity here, or does the entity here name a successor that has one". Those negatives must retire,
     * or a merged id that was once recorded as missing would stay missing for the whole TTL.
     *
     * <p>Bumped to {@code v5-definition-sources} on 2026-11-16 for the page question again: the term
     * stage now asks whether a <em>configured</em> provider publishes the prefix's definitions and
     * synonyms before it records a page's silence as
     * {@code no page text for this prefix}. A prefix with no such source ({@code direct} mode, and FMA
     * under an OLS4-only {@code providers} run) is reported
     * {@code no text source configured for this prefix}, transient and uncached — a fact about the
     * configuration, not the term. A {@code DEFINITIVE} {@code no page text} negative recorded by the
     * ANNO-1d page question still carries {@code v4-merged-ids}, answered the page question under the
     * old rule, and must retire and be re-asked as the new, source-aware question.
     */
    static final String RESOLUTION_STRATEGY = "v5-definition-sources";

    /** The canonical identities.org form used by the term-stage page fallback. */
    public static final String IDENTIFIERS_ORG_PAGE_PREFIX = "https://identifiers.org/";

    private static final String MIRIAM_URN_PREFIX = "urn:miriam:";
    private static final String IDENTIFIERS_ORG_HOST = "identifiers.org";
    private static final String PURL_OBO_PREFIX = "http://purl.obolibrary.org/obo/";
    private static final String HTTPS_PURL_OBO_PREFIX = "https://purl.obolibrary.org/obo/";
    /** identifiers.org's namespace modifier for OBO-shaped ids: {@code /obo/GO_0008150}. */
    private static final String OBO_NAMESPACE = "obo";

    private static final AnnotationPrefixCatalogue INSTANCE = new AnnotationPrefixCatalogue();

    private final Map<String, PrefixEntry> byCanonical;
    private final Map<String, PrefixEntry> byAlias;

    private AnnotationPrefixCatalogue() {
        var entries = List.of(
                new PrefixEntry(
                        "go",
                        List.of("obo.go"),
                        true,
                        "GO",
                        "http://purl.obolibrary.org/obo/GO_{id}",
                        "go",
                        "GO:{id}",
                        false,
                        Set.of(TermDefinition.Source.BIOPORTAL, TermDefinition.Source.OLS4)),
                new PrefixEntry(
                        "chebi",
                        List.of("obo.chebi"),
                        true,
                        "CHEBI",
                        "http://purl.obolibrary.org/obo/CHEBI_{id}",
                        "chebi",
                        "CHEBI:{id}",
                        true,
                        Set.of(TermDefinition.Source.BIOPORTAL, TermDefinition.Source.OLS4)),
                new PrefixEntry(
                        "fma",
                        List.of(),
                        true,
                        "FMA",
                        "http://purl.org/sig/ont/fma/fma{id}",
                        "fma",
                        "FMA:{id}",
                        false,
                        Set.of(TermDefinition.Source.BIOPORTAL)),
                new PrefixEntry(
                        "sbo",
                        List.of("biomodels.sbo"),
                        true,
                        "SBO",
                        "http://biomodels.net/SBO/SBO_{id}",
                        "sbo",
                        "SBO:{id}",
                        false,
                        Set.of(TermDefinition.Source.BIOPORTAL, TermDefinition.Source.OLS4)),
                new PrefixEntry(
                        "kisao",
                        List.of("biomodels.kisao"),
                        true,
                        "KISAO",
                        "http://www.biomodels.net/kisao/KISAO#KISAO_{id}",
                        "kisao",
                        "biomodels.kisao:KISAO_{id}",
                        false,
                        Set.of(TermDefinition.Source.BIOPORTAL, TermDefinition.Source.OLS4)));

        var canonical = new LinkedHashMap<String, PrefixEntry>();
        var aliases = new LinkedHashMap<String, PrefixEntry>();
        for (var entry : entries) {
            canonical.put(entry.canonicalPrefix(), entry);
            for (var alias : entry.aliases()) {
                aliases.put(alias, entry);
            }
        }
        this.byCanonical = Map.copyOf(canonical);
        this.byAlias = Map.copyOf(aliases);
    }

    public static AnnotationPrefixCatalogue standard() {
        return INSTANCE;
    }

    /** The canonical entries of the five ontological prefixes, in table order. */
    public List<PrefixEntry> ontologies() {
        return List.of(
                byCanonical.get("go"),
                byCanonical.get("chebi"),
                byCanonical.get("fma"),
                byCanonical.get("sbo"),
                byCanonical.get("kisao"));
    }

    /**
     * Whether a prefix (canonical or alias) names an ontology. Unknown prefixes and every
     * non-ontological identifier namespace answer {@code false}.
     */
    public boolean isOntological(String prefix) {
        return entry(prefix).isPresent();
    }

    /** The entry for a prefix or one of its aliases; aliases and case resolve to the canonical entry. */
    public Optional<PrefixEntry> entry(String prefix) {
        var normalized = normalizeToken(prefix);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        var canonical = byCanonical.get(normalized);
        if (canonical != null) {
            return Optional.of(canonical);
        }
        return Optional.ofNullable(byAlias.get(normalized));
    }

    /**
     * The term key for an annotation URI or URN, in any of the shapes the corpus showed: MIRIAM
     * URNs, identifiers.org paths in both spellings, identifiers.org's {@code obo/} namespace
     * spelling, and direct OBO purl URLs.
     *
     * <p>Empty for anything else — a plain page, a DOI, a KEGG id — because that is what routes it
     * to the resource path. Never throws.
     */
    public Optional<TermKey> termKey(String uri) {
        try {
            return termKeyUnchecked(uri);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<TermKey> termKeyUnchecked(String uri) {
        if (uri == null || uri.isBlank()) {
            return Optional.empty();
        }
        var candidate = uri.trim().replace("&#58;", ":").replace("&#47;", "/");

        var lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.startsWith(MIRIAM_URN_PREFIX)) {
            var body = candidate.substring(MIRIAM_URN_PREFIX.length());
            try {
                return Optional.of(miriamUrn(body));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }
        if (lower.startsWith(PURL_OBO_PREFIX)) {
            return fromOboLocalId(candidate.substring(PURL_OBO_PREFIX.length()));
        }
        if (lower.startsWith(HTTPS_PURL_OBO_PREFIX)) {
            return fromOboLocalId(candidate.substring(HTTPS_PURL_OBO_PREFIX.length()));
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return fromIdentifiersOrgPath(candidate);
        }
        var separator = separatorIndex(candidate);
        if (separator > 0 && separator < candidate.length() - 1) {
            return termKey(candidate.substring(0, separator), candidate.substring(separator + 1));
        }
        return Optional.empty();
    }

    /**
     * A MIRIAM URN body, {@code <prefix>:<id>}. An identifier may itself carry a colon — the census
     * found {@code biomodels.kisao:KISAO:0000019} — so the prefix is taken from the shortest colon
     * pair that names a catalogue entry.
     */
    private TermKey miriamUrn(String body) {
        var index = body.indexOf(':');
        while (index > 0 && index < body.length() - 1) {
            var token = body.substring(0, index);
            if (entry(token).isPresent()) {
                var localId = body.substring(index + 1);
                return termKey(token, localId).orElseThrow(IllegalArgumentException::new);
            }
            index = body.indexOf(':', index + 1);
        }
        throw new IllegalArgumentException("No ontological prefix in MIRIAM URN body: " + body);
    }

    /**
     * Builds a term key from a prefix (canonical or alias) and a local id in any of the prefix
     * spellings the census found: {@code GO:0008150}, {@code GO_0008150}, {@code KISAO_0000019},
     * {@code KISAO:0000019}. Empty when the prefix is not ontological.
     */
    public Optional<TermKey> termKey(String prefix, String localId) {
        if (localId == null || localId.isBlank()) {
            return Optional.empty();
        }
        var id = stripPrefixToken(decode(localId.trim()));
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return entry(prefix).map(entry -> new TermKey(entry.canonicalPrefix(), id));
    }

    /**
     * The class IRI both providers are asked for, for example
     * {@code http://purl.obolibrary.org/obo/GO_0008150}.
     *
     * <p>One column, not one per provider, because BioPortal and OLS4 were measured on 2026-11-13
     * and agree on all five prefixes: the OBO purl for GO and ChEBI, {@code purl.org/sig} for FMA,
     * {@code biomodels.net} for SBO and the KISAO hash URL. A provider that ever diverges gets its
     * own column, measured, not guessed.
     */
    public Optional<String> classIri(TermKey key) {
        Objects.requireNonNull(key, "key");
        return entry(key.prefix())
                .map(entry -> entry.classIriTemplate()
                        .replace("{id}", key.id()));
    }

    /**
     * What a lookup for this term depends on, as one comparable string: the class IRI and both
     * provider ontology ids.
     *
     * <p>It exists so a cached <em>negative</em> answer can say which question produced it. A
     * {@code DEFINITIVE} "no such class" is only as good as the template that asked, and a wrong
     * template turns every term of a prefix into a cached 404 for the whole TTL (measured: the SBO and
     * FMA 404s of 2026-11-13). Recording this string lets the resolver treat a negative produced under
     * a different catalogue as a miss, which is how such an entry heals itself instead of waiting.
     */
    public Optional<String> lookupFingerprint(TermKey key) {
        Objects.requireNonNull(key, "key");
        return entry(key.prefix()).map(entry -> RESOLUTION_STRATEGY
                + "|" + entry.classIriTemplate()
                + "|" + entry.bioportalOntology()
                + "|" + entry.ols4Ontology());
    }

    /** The BioPortal ontology acronym, for example {@code GO}; empty for a non-ontological prefix. */
    public Optional<String> bioportalOntology(TermKey key) {
        Objects.requireNonNull(key, "key");
        return entry(key.prefix()).map(PrefixEntry::bioportalOntology);
    }

    /**
     * The OLS4 ontology id, for example {@code go}. Empty only for a non-ontological prefix.
     */
    public Optional<String> ols4Ontology(TermKey key) {
        Objects.requireNonNull(key, "key");
        return entry(key.prefix()).flatMap(entry -> Optional.ofNullable(entry.ols4Ontology()));
    }

    /**
     * The canonical page URL used by the term-stage page fallback: {@code https://identifiers.org/} plus
     * the row's compact identifier, for example {@code https://identifiers.org/GO:0008150}.
     *
     * <p>The compact form is not {@code <prefix>:<id>}: since {@code namespaceEmbeddedInLui} is set for
     * GO, ChEBI, FMA and SBO, their local id itself carries the prefix token, so those rows embed it in
     * the template. Measured 2026-11-16: the resolver answers 400 for {@code go:0008150} and 200 for
     * {@code GO:0008150}. The local id is substituted verbatim — FMA ids exist in two widths
     * ({@code 62387}, {@code 0325906}) and are never re-padded.
     */
    public Optional<String> canonicalPageUrl(TermKey key) {
        Objects.requireNonNull(key, "key");
        return entry(key.prefix())
                .map(entry -> IDENTIFIERS_ORG_PAGE_PREFIX
                        + entry.compactIdentifierTemplate().replace("{id}", key.id()));
    }

    /**
     * Whether the page fallback can learn anything from this prefix's identifiers.org page.
     *
     * <p>{@code false} for GO, FMA, SBO and KiSAO: measured 2026-11-16, GO answers a plain client with
     * 403, the FMA and KiSAO pages are OLS4 single-page-app shells that serve no entity text, and SBO
     * redirects to a deprecation notice for one <em>resource</em>. Only the server-rendered ChEBI page
     * yields entity text. A {@code false} row must not be fetched at all — the request could only fail.
     */
    public Optional<Boolean> pageTextAvailable(TermKey key) {
        Objects.requireNonNull(key, "key");
        return entry(key.prefix()).map(PrefixEntry::pageYieldsText);
    }

    /**
     * The providers that publish this prefix's <em>definitions and synonyms</em>, as opposed to a
     * label alone.
     *
     * <p>Every prefix but FMA is served with text by both BioPortal and OLS4. FMA's OLS4
     * configuration indexes no definition property and no synonym property, measured 2026-11-16 in
     * the v1 ontology document {@code /ontologies/fma} ({@code config.definitionProperties: []},
     * {@code config.synonymProperties: []}; the v2 document carries no such fields) and against the
     * pair {@code FMA:305751}: OLS4 answers its label only, BioPortal answers a definition and the
     * synonym "Biological structure". The declaration exists so
     * a run with no configured source for FMA says "no text source configured for this prefix"
     * instead of turning OLS4's label-only answer, or the absence of a page, into a claim that FMA
     * publishes no definitions.
     *
     * <p>An unknown prefix has no configured source; the term stage never asks about one.
     */
    public Set<TermDefinition.Source> definitionSources(TermKey key) {
        Objects.requireNonNull(key, "key");
        return entry(key.prefix()).map(PrefixEntry::definitionSources).orElse(Set.of());
    }

    private Optional<TermKey> fromOboLocalId(String localId) {
        var raw = decode(localId.trim());
        var separator = separatorIndex(raw);
        if (separator <= 0) {
            return Optional.empty();
        }
        var token = raw.substring(0, separator);
        var id = raw.substring(separator + 1).trim();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return entry(token).map(entry -> new TermKey(entry.canonicalPrefix(), id));
    }

    private Optional<TermKey> fromIdentifiersOrgPath(String url) {
        var afterScheme = url.substring(url.indexOf("//") + 2);
        var slashIndex = afterScheme.indexOf('/');
        if (slashIndex <= 0) {
            return Optional.empty();
        }
        var host = afterScheme.substring(0, slashIndex).toLowerCase(Locale.ROOT);
        if (!host.equals(IDENTIFIERS_ORG_HOST) && !host.equals("www." + IDENTIFIERS_ORG_HOST)) {
            return Optional.empty();
        }
        var path = afterScheme.substring(slashIndex + 1);
        var queryIndex = indexOfAny(path, '?', '#');
        if (queryIndex >= 0) {
            path = path.substring(0, queryIndex);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isBlank()) {
            return Optional.empty();
        }

        var colonIndex = path.indexOf(':');
        var slash = path.indexOf('/');
        // The prefix is the first path segment: /go:0008150 and /chebi/CHEBI:15377 are both valid.
        var namespaceEnd = colonIndex > 0 && (slash < 0 || colonIndex < slash) ? colonIndex : slash;
        if (namespaceEnd > 0 && namespaceEnd < path.length() - 1) {
            var namespace = path.substring(0, namespaceEnd);
            var localId = path.substring(namespaceEnd + 1);
            if (namespace.equalsIgnoreCase(OBO_NAMESPACE)) {
                return fromOboLocalId(localId);
            }
            if (localId.indexOf('/') >= 0) {
                return Optional.empty();
            }
            if (localId.indexOf(':') >= 0) {
                var parts = localId.split(":", 2);
                return termKey(namespace, parts[1]);
            }
            return termKey(namespace, localId);
        }
        return Optional.empty();
    }

    /**
     * The local identifier without a leading prefix token and its separator, decoded once. Purl and
     * MIRIAM forms URL-encode the separator ({@code GO%3A0008150}), so decoding happens first.
     */
    private static String stripPrefixToken(String rawId) {
        var id = rawId;
        var separator = separatorIndex(id);
        if (separator > 0 && separator < id.length() - 1) {
            id = id.substring(separator + 1);
        }
        return id.trim();
    }


    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String decode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private static int indexOfAny(String value, char first, char second) {
        var a = value.indexOf(first);
        var b = value.indexOf(second);
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private static int separatorIndex(String value) {
        var colon = value.indexOf(':');
        var underscore = value.indexOf('_');
        if (colon < 0) {
            return underscore;
        }
        if (underscore < 0) {
            return colon;
        }
        return Math.min(colon, underscore);
    }

    /** One catalogue row for an ontological prefix. */
    public record PrefixEntry(
            String canonicalPrefix,
            List<String> aliases,
            boolean ontological,
            String bioportalOntology,
            String classIriTemplate,
            String ols4Ontology,
            String compactIdentifierTemplate,
            boolean pageYieldsText,
            Set<TermDefinition.Source> definitionSources
    ) {
        public PrefixEntry {
            Objects.requireNonNull(canonicalPrefix, "canonicalPrefix");
            Objects.requireNonNull(aliases, "aliases");
            Objects.requireNonNull(bioportalOntology, "bioportalOntology");
            Objects.requireNonNull(classIriTemplate, "classIriTemplate");
            Objects.requireNonNull(compactIdentifierTemplate, "compactIdentifierTemplate");
            definitionSources = Set.copyOf(Objects.requireNonNull(definitionSources, "definitionSources"));
            aliases = List.copyOf(aliases);
        }
    }
}

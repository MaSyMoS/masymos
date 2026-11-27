package org.masymos.core.units;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A unit after reduction: its stable identity, its base dimension, and the named units it is
 * defined from.
 *
 * <p>{@code key} is built from the <em>flattened base composition</em> (design 4.1), so the same
 * definition in two models or two formats yields the same key and therefore one graph node.
 * {@code components} holds the <em>named</em> units, which is what {@code COMPOSED_OF} traverses;
 * the flattened key alone would lose, for example, the litre inside nanomolar.
 *
 * <p>{@code dimension} is <em>output</em>, not input: {@link #composition()} derives the base
 * exponents from {@code components}, so a producer that writes the dimension in another spelling
 * cannot change an identity or make a resolve fail.
 */
public record CanonicalUnit(
        String key,
        String display,
        List<String> names,
        String kind,
        String dimension,
        String multiplier,
        String offset,
        List<UnitComponent> components) {

    /** A flattened unit: its multiplier and its base exponents, which is what identity needs. */
    public record Composition(BigDecimal multiplier, Map<String, BigDecimal> bases) {}

    private static final String DERIVED_KEY_PREFIX = "unit:";
    private static final String BASE_KEY_PREFIX = "base:";
    private static final String DECLARED_BASE_KEY_PREFIX = "declared-base:";
    private static final String UNRESOLVED_KEY_PREFIX = "unresolved:";
    private static final String DIMENSIONLESS = "dimensionless";

    public CanonicalUnit {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(display, "display");
        names = List.copyOf(names);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(multiplier, "multiplier");
        components = List.copyOf(components);
    }

    /**
     * The flattened composition of this unit: its own multiplier, which the resolver already reduced,
     * and the base exponents summed over {@code components} with each component's exponent applied.
     *
     * <p>The component keys are read in the canonical key spelling only {@link UnitCatalogue}
     * produces - {@code base:metre}, {@code declared-base:u}, {@code unit:0.001*metre^3} - so the
     * parse cannot meet a foreign spelling, which is what {@code dimension} could.
     */
    public Composition composition() {
        var bases = new TreeMap<String, BigDecimal>();
        for (var component : components) {
            var exponent = new BigDecimal(component.exponent());
            for (var base : basesOfKey(component.key()).entrySet()) {
                bases.merge(base.getKey(), base.getValue().multiply(exponent), BigDecimal::add);
            }
        }
        bases.entrySet().removeIf(base -> base.getValue().signum() == 0 || DIMENSIONLESS.equals(base.getKey()));
        return new Composition(new BigDecimal(multiplier), Map.copyOf(bases));
    }

    /** The base exponents one canonical key stands for; its multiplier is already in the parent's. */
    private static Map<String, BigDecimal> basesOfKey(String key) {
        var named = namedBase(key);
        if (named != null) {
            return DIMENSIONLESS.equals(named) ? Map.of() : Map.of(named, BigDecimal.ONE);
        }
        if (!key.startsWith(DERIVED_KEY_PREFIX)) {
            throw new IllegalArgumentException("not a canonical unit key: " + key);
        }
        var text = key.substring(DERIVED_KEY_PREFIX.length());
        // An offset is a suffix of the identity ("unit:1*kelvin^1+273.15"); the base part precedes it.
        var offset = text.indexOf('+');
        if (offset >= 0) {
            text = text.substring(0, offset);
        }
        var star = text.indexOf('*');
        if (star < 0) {
            throw new IllegalArgumentException("not a canonical unit key: " + key);
        }
        var bases = new TreeMap<String, BigDecimal>();
        var dimension = text.substring(star + 1);
        if (DIMENSIONLESS.equals(dimension)) {
            return Map.of();
        }
        for (var part : dimension.split("\\*")) {
            var at = part.lastIndexOf('^');
            if (at < 0) {
                throw new IllegalArgumentException("not a base dimension in unit key: " + key);
            }
            bases.put(part.substring(0, at), new BigDecimal(part.substring(at + 1)));
        }
        return bases;
    }

    /** The base name a key that denotes one base carries, or {@code null} for a composed key. */
    private static String namedBase(String key) {
        for (var prefix : List.of(BASE_KEY_PREFIX, DECLARED_BASE_KEY_PREFIX, UNRESOLVED_KEY_PREFIX)) {
            if (key.startsWith(prefix)) {
                return key.substring(prefix.length());
            }
        }
        return null;
    }
}

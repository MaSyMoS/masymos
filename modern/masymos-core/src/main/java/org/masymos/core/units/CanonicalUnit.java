package org.masymos.core.units;

import java.util.List;
import java.util.Objects;

/**
 * A unit after reduction: its stable identity, its base dimension, and the named units it is
 * defined from.
 *
 * <p>{@code key} is built from the <em>flattened base composition</em> (design 4.1), so the same
 * definition in two models or two formats yields the same key and therefore one graph node.
 * {@code components} holds the <em>named</em> units, which is what {@code COMPOSED_OF} traverses;
 * the flattened key alone would lose, for example, the litre inside nanomolar.
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

    public CanonicalUnit {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(display, "display");
        names = List.copyOf(names);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(multiplier, "multiplier");
        components = List.copyOf(components);
    }
}

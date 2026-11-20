package org.masymos.core.units;

import java.util.Objects;

/**
 * One named unit inside another unit's definition: the {@code COMPOSED_OF} edge of the graph.
 *
 * <p>{@code key} is the canonical key of the referenced unit (a base such as {@code base:mole} or a
 * reduction such as {@code unit:0.001*metre^3}); the exponent and multiplier are those of the
 * reference, so the edge is part of the definition's identity.
 */
public record UnitComponent(String key, String exponent, String multiplier) {
    public UnitComponent {
        Objects.requireNonNull(key, "key");
        exponent = exponent == null || exponent.isBlank() ? "1" : exponent;
        multiplier = multiplier == null || multiplier.isBlank() ? "1" : multiplier;
    }
}

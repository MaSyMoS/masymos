package org.masymos.core.query.fusion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-ranker weights. A ranker without an entry weighs {@code 1.0}. The map is held by a defensive
 * copy, and a null or non-positive weight is rejected rather than silently ignored, so a bad
 * request surface can turn it into a {@code 400}.
 */
public record FusionWeights(Map<Ranker, Double> weights) {
    public FusionWeights {
        weights = weights == null ? Map.of() : new LinkedHashMap<>(weights);
        weights.forEach((ranker, weight) -> {
            Objects.requireNonNull(ranker, "ranker");
            if (weight == null || weight <= 0) {
                throw new IllegalArgumentException("weight must be > 0: " + ranker);
            }
        });
        weights = Collections.unmodifiableMap(weights);
    }

    /** The weight of one ranker; {@code 1.0} when the caller did not specify one. */
    public double weight(Ranker ranker) {
        Double weight = weights.get(ranker);
        return weight == null ? 1.0 : weight;
    }

    /** Weights for a request that did not specify any: every ranker weighs {@code 1.0}. */
    public static FusionWeights none() {
        return new FusionWeights(Map.of());
    }
}

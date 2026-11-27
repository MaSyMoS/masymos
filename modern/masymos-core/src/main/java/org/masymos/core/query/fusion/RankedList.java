package org.masymos.core.query.fusion;

import java.util.List;
import java.util.Objects;

/** One ranker's ordered input to the fusion. The hits are held by a defensive copy. */
public record RankedList(Ranker ranker, List<RankedModel> hits) {
    public RankedList {
        Objects.requireNonNull(ranker, "ranker");
        hits = hits == null ? List.<RankedModel>of() : List.copyOf(hits);
    }
}

package org.masymos.core.extract;

import org.masymos.core.model.ModelIdentifier;

public record IndexedModel(
        ModelSource source,
        ModelIdentifier identifier
) {
}

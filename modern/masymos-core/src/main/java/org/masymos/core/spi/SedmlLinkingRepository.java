package org.masymos.core.spi;

import org.masymos.core.model.SedmlLinkingResult;

public interface SedmlLinkingRepository {
    SedmlLinkingResult linkSedmlReferences();
}

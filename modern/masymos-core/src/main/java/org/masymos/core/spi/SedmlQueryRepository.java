package org.masymos.core.spi;

import java.util.List;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.SedmlSearchResult;

/**
 * Finds SED-ML descriptions as their own result type, with the model sources each one references.
 * The request is a model query request: the free-text keyword, the SED-ML clauses, {@code top} and
 * {@code minScore} apply; {@code modelTypes} and version grouping do not, because every hit is a
 * {@code :SEDML_MODEL}.
 */
public interface SedmlQueryRepository {
    List<SedmlSearchResult> querySedml(ModelQueryRequest request);
}

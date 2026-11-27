package org.masymos.core.spi;

import java.util.List;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.Page;
import org.masymos.core.query.SedmlSearchResult;

/**
 * Finds SED-ML descriptions as their own result type, with the model sources each one references.
 * The request is a model query request: the free-text keyword, the SED-ML clauses, {@code top} and
 * {@code minScore} apply; {@code modelTypes} and version grouping do not, because every hit is a
 * {@code :SEDML_MODEL}.
 */
public interface SedmlQueryRepository {
    List<SedmlSearchResult> querySedml(ModelQueryRequest request);

    /**
     * The same SED-ML query as a keyset page ({@code OPN-6}), plus the position to continue from.
     *
     * <p>A store that cannot page must not pretend it can: the default throws rather than answering one
     * page with no cursor, because "no cursor" is a promise that nothing was left out
     * ({@link ModelRepository#queryModelsPage} states the same rule).
     *
     * @throws UnsupportedOperationException from an implementation that has no keyset
     */
    default Page<SedmlSearchResult> querySedmlPage(ModelQueryRequest request) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot page SED-ML queries");
    }
}

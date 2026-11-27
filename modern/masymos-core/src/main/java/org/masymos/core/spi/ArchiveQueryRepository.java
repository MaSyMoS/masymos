package org.masymos.core.spi;

import java.util.List;
import org.masymos.core.query.ArchiveSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.Page;

/**
 * Finds COMBINE archives as their own result type. The request is a model query request: the
 * free-text keyword, the {@code ARCHIVE_NAME}/{@code ARCHIVE_URI} clauses, {@code top} and
 * {@code minScore} apply; {@code modelTypes} and version grouping do not.
 */
public interface ArchiveQueryRepository {
    List<ArchiveSearchResult> queryArchives(ModelQueryRequest request);

    /**
     * The same archive query as a keyset page ({@code OPN-6}), plus the position to continue from.
     *
     * <p>A store that cannot page must not pretend it can: the default throws rather than answering one
     * page with no cursor, because "no cursor" is a promise that nothing was left out
     * ({@link ModelRepository#queryModelsPage} states the same rule).
     *
     * @throws UnsupportedOperationException from an implementation that has no keyset
     */
    default Page<ArchiveSearchResult> queryArchivesPage(ModelQueryRequest request) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot page archive queries");
    }
}

package org.masymos.core.spi;

import java.util.List;
import org.masymos.core.query.ArchiveSearchResult;
import org.masymos.core.query.ModelQueryRequest;

/**
 * Finds COMBINE archives as their own result type. The request is a model query request: the
 * free-text keyword, the {@code ARCHIVE_NAME}/{@code ARCHIVE_URI} clauses, {@code top} and
 * {@code minScore} apply; {@code modelTypes} and version grouping do not.
 */
public interface ArchiveQueryRepository {
    List<ArchiveSearchResult> queryArchives(ModelQueryRequest request);
}

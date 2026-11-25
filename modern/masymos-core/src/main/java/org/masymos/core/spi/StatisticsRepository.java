package org.masymos.core.spi;

import org.masymos.core.model.StoreStatistics;

/**
 * Read-only counts over the store, for {@code GET /api/v1/stats} and the {@code stats} command.
 *
 * <p>A backend may throw {@code StorageUnavailableException} when it cannot be asked; it must not
 * answer with zeroes, because "no models" and "cannot tell" are different facts.</p>
 */
public interface StatisticsRepository {
    /** Counts of the documents, models, model kinds, persons, publications, annotations and archives. */
    StoreStatistics statistics();
}

package org.masymos.core.spi;

import java.util.List;
import org.masymos.core.model.IndexSummary;

/**
 * Read-only catalogue of the indexes the store keeps, for operators and the {@code analyze} command.
 *
 * <p>It reports what the store says, not what the schema initializer intended; a backend may throw
 * {@code StorageUnavailableException} when it cannot be asked.</p>
 */
public interface IndexCatalog {
    /** Every index known to the store, ordered by name. */
    List<IndexSummary> list();
}

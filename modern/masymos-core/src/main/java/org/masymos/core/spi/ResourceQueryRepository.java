package org.masymos.core.spi;

import java.util.List;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.Page;
import org.masymos.core.query.PersonSearchResult;
import org.masymos.core.query.PublicationSearchResult;

public interface ResourceQueryRepository {
    List<AnnotationSearchResult> queryAnnotations(ModelQueryRequest request);

    List<PersonSearchResult> queryPersons(ModelQueryRequest request);

    List<PublicationSearchResult> queryPublications(ModelQueryRequest request);

    /**
     * The same annotation query as a keyset page ({@code OPN-6}), plus the position to continue from.
     *
     * <p>A store that cannot page must not pretend it can: the default throws rather than answering one
     * page with no cursor, because "no cursor" is a promise that nothing was left out
     * ({@link ModelRepository#queryModelsPage} states the same rule).
     *
     * @throws UnsupportedOperationException from an implementation that has no keyset
     */
    default Page<AnnotationSearchResult> queryAnnotationsPage(ModelQueryRequest request) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot page annotation queries");
    }

    /** The person lane's keyset page ({@code OPN-6}); the contract is {@link #queryAnnotationsPage}. */
    default Page<PersonSearchResult> queryPersonsPage(ModelQueryRequest request) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot page person queries");
    }

    /** The publication lane's keyset page ({@code OPN-6}); the contract is {@link #queryAnnotationsPage}. */
    default Page<PublicationSearchResult> queryPublicationsPage(ModelQueryRequest request) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot page publication queries");
    }
}

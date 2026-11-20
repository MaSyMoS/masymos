package org.masymos.core.spi;

import java.util.List;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.PersonSearchResult;
import org.masymos.core.query.PublicationSearchResult;

public interface ResourceQueryRepository {
    List<AnnotationSearchResult> queryAnnotations(ModelQueryRequest request);

    List<PersonSearchResult> queryPersons(ModelQueryRequest request);

    List<PublicationSearchResult> queryPublications(ModelQueryRequest request);
}

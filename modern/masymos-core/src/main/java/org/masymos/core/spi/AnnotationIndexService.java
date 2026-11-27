package org.masymos.core.spi;

import org.masymos.core.model.AnnotationIndexingOptions;
import org.masymos.core.model.AnnotationIndexingResult;

public interface AnnotationIndexService {
    AnnotationIndexingResult rebuild(AnnotationIndexingOptions options);

    default AnnotationIndexingResult rebuild(boolean dropExistingIndex) {
        return rebuild(AnnotationIndexingOptions.defaults().withDropExistingIndex(dropExistingIndex));
    }
}

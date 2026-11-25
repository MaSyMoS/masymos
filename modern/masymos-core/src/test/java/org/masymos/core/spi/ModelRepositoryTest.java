package org.masymos.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelConflictException;
import org.masymos.core.model.ModelDeletionResult;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.model.ModelVersion;
import org.masymos.core.model.StoredDocument;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;

class ModelRepositoryTest {
    @Test
    void defaultInsertWithEnforceUniqueFileIdThrowsTheDocumentedConflict() {
        var repository = new ReferenceOnlyRepository();
        var reference = new ModelReference("m.sbml", URI.create("file:///models/m.sbml"), ModelType.SBML);

        var conflict = assertThrows(ModelConflictException.class, () -> repository.insert(reference, true));

        assertTrue(
                conflict.getMessage().contains("unique fileId"),
                "the refusal must name the guard it cannot enforce: " + conflict.getMessage());
        assertTrue(repository.inserted.isEmpty(), "a refused insert must not reach the plain insert");

        assertEquals(new ModelIdentifier("uid-1", "m.sbml"), repository.insert(reference, false));
        assertEquals(List.of(reference), repository.inserted);
    }

    /** A repository that knows the plain insert only, so the guarded one reaches the interface default. */
    private static final class ReferenceOnlyRepository implements ModelRepository {
        private final List<ModelReference> inserted = new ArrayList<>();

        @Override
        public ModelIdentifier insert(ModelReference modelReference) {
            inserted.add(modelReference);
            return new ModelIdentifier("uid-1", modelReference.fileId());
        }

        @Override
        public Optional<ModelIdentifier> findByUid(String uid) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelVersion> findLatestVersion(String fileId) {
            return Optional.empty();
        }

        @Override
        public List<ModelVersion> findVersions(String fileId) {
            return List.of();
        }

        @Override
        public Optional<ModelVersion> findVersion(String fileId, String versionId) {
            return Optional.empty();
        }

        @Override
        public Optional<StoredDocument> findDocument(String uid) {
            return Optional.empty();
        }

        @Override
        public List<ModelSearchResult> queryModels(ModelQueryRequest request) {
            return List.of();
        }

        @Override
        public Optional<ModelDeletionResult> deleteByUid(
                String uid, String expectedFileId, String expectedVersionId) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelDeletionResult> deleteByFileId(String fileId) {
            return Optional.empty();
        }
    }
}

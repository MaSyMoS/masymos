package org.masymos.core.spi;

import java.util.List;
import java.util.Optional;
import org.masymos.core.model.ModelConflictException;
import org.masymos.core.model.ModelDeletionResult;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelVersion;
import org.masymos.core.model.StoredDocument;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.Page;

public interface ModelRepository {
    ModelIdentifier insert(ModelReference modelReference);

    /**
     * Inserts a document, optionally refusing a {@code fileId} that already has a stored document.
     *
     * <p>The guard is the legacy {@code enforceUniqueFileID} of the non-versioned insert: it is
     * off by default because a repeated {@code fileId} is how a new version is added. When on and a
     * document with that {@code fileId} exists, {@link org.masymos.core.model.ModelConflictException}
     * is thrown and nothing is written.
     *
     * <p>This default cannot inspect the store, so it cannot tell a duplicate from a free
     * {@code fileId}: a guarded insert is refused with the same {@link ModelConflictException}
     * rather than written unguarded.
     */
    default ModelIdentifier insert(ModelReference modelReference, boolean enforceUniqueFileId) {
        if (enforceUniqueFileId) {
            throw ModelConflictException.guardViolation("this repository cannot enforce a unique fileId");
        }
        return insert(modelReference);
    }

    /**
     * Resolves a document by its canonical string uid, or — for clients that still hold a legacy
     * identifier — by the numeric legacy uid the value represents (decision D1). A value that is not
     * a decimal integer is only ever matched against {@code uid}.
     */
    Optional<ModelIdentifier> findByUid(String uid);

    /**
     * Returns the current version of a model file: the document with that {@code fileId} and no
     * outgoing {@code HAS_SUCCESSOR} relationship. Empty when no document with that {@code fileId}
     * exists.
     */
    Optional<ModelVersion> findLatestVersion(String fileId);

    /**
     * Returns every stored version of a model file, oldest first ({@code legacyUid} ascending).
     * An unknown {@code fileId} yields an empty list, not {@code null}.
     */
    List<ModelVersion> findVersions(String fileId);

    /**
     * Returns the version of a model file carrying the given external label. When several stored
     * versions share that label, the newest one ({@code legacyUid} descending) wins. Empty when no
     * document matches both {@code fileId} and {@code versionId}.
     */
    Optional<ModelVersion> findVersion(String fileId, String versionId);

    /**
     * Returns the stored document identified by its canonical uid or numeric legacy uid, including
     * the source content captured when it was indexed (STO-2). Empty when no document matches.
     */
    Optional<StoredDocument> findDocument(String uid);

    List<ModelSearchResult> queryModels(ModelQueryRequest request);

    /**
     * The same query as a keyset page ({@code OPN-6}), plus the position to continue from.
     *
     * <p>A store that cannot page must not pretend it can: the default throws rather than answering one
     * page with no cursor, because "no cursor" is a promise that nothing was left out, and a truncated
     * answer that says so would be worse than a refusal.
     *
     * @throws UnsupportedOperationException from an implementation that has no keyset
     */
    default Page<ModelSearchResult> queryModelsPage(ModelQueryRequest request) {
        throw new UnsupportedOperationException(getClass().getName() + " cannot page model queries");
    }

    /**
     * Removes one document with every node it owns, relinks its version-chain neighbours and purges
     * annotation nodes that lost their last relationship (STO-4).
     *
     * <p>{@code expectedFileId} and {@code expectedVersionId} are the legacy delete guards: when
     * given and they do not match the stored document, nothing is deleted and
     * {@link org.masymos.core.model.ModelConflictException} is thrown. Empty means no document
     * matches the uid.
     */
    Optional<ModelDeletionResult> deleteByUid(String uid, String expectedFileId, String expectedVersionId);

    /**
     * Removes every version of a model file. Empty when no document carries that {@code fileId}.
     */
    Optional<ModelDeletionResult> deleteByFileId(String fileId);
}

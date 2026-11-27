package org.masymos.service.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelConflictException;
import org.masymos.core.model.ModelDeletionResult;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelVersion;
import org.masymos.core.model.StoredDocument;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.spi.ModelRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ModelControllerTest {
    @Test
    void returnsValidationProblemForInvalidInsertRequest() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty()));

        mockMvc.perform(post("/api/v1/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileId": "",
                                  "url": null,
                                  "modelType": null
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void echoesVersionAndLegacyUidForValidInsertRequest() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty()));

        mockMvc.perform(post("/api/v1/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileId": "BIOMD1",
                                  "url": "https://example.org/model.xml",
                                  "modelType": "SBML",
                                  "versionId": "2014-01-01"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").value("test-uid"))
                .andExpect(jsonPath("$.versionId").value("2014-01-01"))
                .andExpect(jsonPath("$.legacyUid").value(7));
    }

    @Test
    void returnsProblemWhenDeletingMissingModel() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty()));

        mockMvc.perform(delete("/api/v1/models/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("model not found"));
    }

    @Test
    void returnsLatestVersionForModelFile() throws Exception {
        var latestVersion = Optional.of(new ModelVersion("version-uid", "BIOMD1", "v3", 42L));
        var mockMvc = mockMvc(new FakeModelRepository(false, latestVersion, List.of(), Optional.empty()));

        mockMvc.perform(get("/api/v1/models/BIOMD1/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("version-uid"))
                .andExpect(jsonPath("$.fileId").value("BIOMD1"))
                .andExpect(jsonPath("$.versionId").value("v3"))
                .andExpect(jsonPath("$.legacyUid").value(42));
    }

    @Test
    void returnsProblemWhenLatestVersionIsMissing() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty()));

        mockMvc.perform(get("/api/v1/models/BIOMD1/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("model file not found"));
    }

    @Test
    void returnsAllVersionsOfModelFile() throws Exception {
        var versions = List.of(
                new ModelVersion("version-uid-1", "BIOMD1", "v1", 41L),
                new ModelVersion("version-uid-2", "BIOMD1", "v2", 42L));
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), versions, Optional.empty()));

        mockMvc.perform(get("/api/v1/models/BIOMD1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionId").value("v1"))
                .andExpect(jsonPath("$[1].versionId").value("v2"));
    }

    @Test
    void returnsEmptyListWhenNoVersionIsStoredForModelFile() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty()));

        mockMvc.perform(get("/api/v1/models/BIOMD1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void returnsOneVersionByItsExternalLabel() throws Exception {
        var version = Optional.of(new ModelVersion("version-uid", "BIOMD1", "v2", 42L));
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), version));

        mockMvc.perform(get("/api/v1/models/BIOMD1/versions/v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("version-uid"))
                .andExpect(jsonPath("$.versionId").value("v2"))
                .andExpect(jsonPath("$.legacyUid").value(42));
    }

    @Test
    void returnsProblemWhenVersionLabelIsUnknown() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty()));

        mockMvc.perform(get("/api/v1/models/BIOMD1/versions/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("model version not found"));
    }

    @Test
    void returnsTheStoredDocumentMetadataForAUID() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty())
                .withDocument(new StoredDocument(
                        "test-uid", "BIOMD1", "v1", 7L, "<sbml/>", "UTF-8", 7, false, Map.of("submitter", "jj"))));

        mockMvc.perform(get("/api/v1/models/test-uid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("test-uid"))
                .andExpect(jsonPath("$.fileId").value("BIOMD1"))
                .andExpect(jsonPath("$.versionId").value("v1"))
                .andExpect(jsonPath("$.legacyUid").value(7))
                .andExpect(jsonPath("$.hasDocument").value(true))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.metadata.submitter").value("jj"));
    }

    @Test
    void returnsProblemWhenTheDocumentIsUnknown() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty()));

        mockMvc.perform(get("/api/v1/models/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("model document not found"));
    }

    @Test
    void downloadsTheStoredDocumentWithItsTruncationHeaders() throws Exception {
        var content = "<sbml>Grüße</sbml>".getBytes(StandardCharsets.UTF_8);
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty())
                .withDocument(new StoredDocument(
                        "test-uid", "BIOMD1", "v1", 7L, "<sbml>Grüße</sbml>", "UTF-8", 19, true, Map.of())));

        mockMvc.perform(get("/api/v1/models/test-uid/document"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(content))
                .andExpect(header().string("X-Masymos-Document-Truncated", "true"))
                .andExpect(header().string("X-Masymos-Document-Original-Bytes", "19"))
                .andExpect(header().string("X-Masymos-Document-Storage-Encoding", "UTF-8"))
                .andExpect(header().string("Content-Disposition", containsString("BIOMD1")));
    }

    @Test
    void returnsProblemWhenTheDocumentHasNoStoredContent() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty())
                .withDocument(new StoredDocument("test-uid", "BIOMD1", null, null, null, null, 0, false, null)));

        mockMvc.perform(get("/api/v1/models/test-uid/document"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("model document has no stored content"));
    }

    @Test
    void deletesADocumentAndReportsWhatWasRemoved() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(true, Optional.empty(), List.of(), Optional.empty())
                .withDeletion(1, 12));

        mockMvc.perform(delete("/api/v1/models/test-uid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(1))
                .andExpect(jsonPath("$.nodes").value(12));
    }

    @Test
    void refusesADeleteWhoseConfirmationDoesNotMatch() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(true, Optional.empty(), List.of(), Optional.empty())
                .withDeletion(1, 12));

        mockMvc.perform(delete("/api/v1/models/test-uid").param("fileId", "other-file"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Model conflict"));
    }

    @Test
    void deletesEveryVersionOfAFile() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(true, Optional.empty(), List.of(), Optional.empty())
                .withDeletion(3, 30));

        mockMvc.perform(delete("/api/v1/models/by-file/BIOMD1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").value(3))
                .andExpect(jsonPath("$.nodes").value(30));
    }

    @Test
    void returnsProblemWhenDeletingAnUnknownFile() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty()));

        mockMvc.perform(delete("/api/v1/models/by-file/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("model file not found"));
    }

    @Test
    void refusesAnInsertThatEnforcesFileIdUniquenessWhenTheFileIdExists() throws Exception {
        var mockMvc = mockMvc(new FakeModelRepository(false, Optional.empty(), List.of(), Optional.empty())
                .refusingDuplicateFileId());

        mockMvc.perform(post("/api/v1/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fileId": "BIOMD1",
                                  "url": "https://example.org/model.xml",
                                  "modelType": "SBML",
                                  "enforceUniqueFileId": true
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Model conflict"));
    }

    private MockMvc mockMvc(ModelRepository repository) {
        return standaloneSetup(new ModelController(repository))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static final class FakeModelRepository implements ModelRepository {
        private final Optional<ModelVersion> latestVersion;
        private final List<ModelVersion> versions;
        private final Optional<ModelVersion> version;
        private Optional<StoredDocument> storedDocument = Optional.empty();
        private Optional<ModelDeletionResult> deletion = Optional.empty();
        private boolean duplicateFileId;

        FakeModelRepository(
                boolean deletionSucceeds,
                Optional<ModelVersion> latestVersion,
                List<ModelVersion> versions,
                Optional<ModelVersion> version) {
            this.latestVersion = latestVersion;
            this.versions = versions;
            this.version = version;
            this.deletion = deletionSucceeds
                    ? Optional.of(new ModelDeletionResult("test-uid", "BIOMD1", 1, 10))
                    : Optional.empty();
        }

        FakeModelRepository withDocument(StoredDocument document) {
            this.storedDocument = Optional.of(document);
            return this;
        }

        FakeModelRepository withDeletion(int documents, long nodes) {
            this.deletion = Optional.of(new ModelDeletionResult("test-uid", "BIOMD1", documents, nodes));
            return this;
        }

        FakeModelRepository refusingDuplicateFileId() {
            this.duplicateFileId = true;
            return this;
        }

        @Override
        public ModelIdentifier insert(ModelReference modelReference) {
            return insert(modelReference, false);
        }

        @Override
        public ModelIdentifier insert(ModelReference modelReference, boolean enforceUniqueFileId) {
            if (enforceUniqueFileId && duplicateFileId) {
                throw ModelConflictException.duplicateFileId(modelReference.fileId());
            }
            return new ModelIdentifier("test-uid", modelReference.fileId(), modelReference.versionId(), 7L);
        }

        @Override
        public Optional<ModelIdentifier> findByUid(String uid) {
            return Optional.empty();
        }

        @Override
        public Optional<ModelVersion> findLatestVersion(String fileId) {
            return latestVersion;
        }

        @Override
        public List<ModelVersion> findVersions(String fileId) {
            return versions;
        }

        @Override
        public Optional<ModelVersion> findVersion(String fileId, String versionId) {
            return version;
        }

        @Override
        public Optional<StoredDocument> findDocument(String uid) {
            return storedDocument;
        }

        @Override
        public List<ModelSearchResult> queryModels(ModelQueryRequest request) {
            return List.of();
        }

        @Override
        public Optional<ModelDeletionResult> deleteByUid(String uid, String expectedFileId, String expectedVersionId) {
            if (expectedFileId != null && !expectedFileId.equals("BIOMD1")) {
                throw ModelConflictException.guardViolation("fileId " + expectedFileId + " does not match");
            }
            return deletion;
        }

        @Override
        public Optional<ModelDeletionResult> deleteByFileId(String fileId) {
            return deletion;
        }
    }
}

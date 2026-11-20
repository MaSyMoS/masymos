package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.masymos.core.spi.ModelRepository;
import org.masymos.service.api.dto.InsertModelRequest;
import org.masymos.service.api.dto.ModelDeletionResponse;
import org.masymos.service.api.dto.ModelDocumentResponse;
import org.masymos.service.api.dto.ModelIdentifierResponse;
import org.masymos.service.api.dto.ModelVersionResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/models")
@Tag(name = "Model storage", description = "Model lifecycle: insertion, retrieval, download and removal.")
public class ModelController {
    private final ModelRepository modelRepository;

    public ModelController(ModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Insert a model",
            description = "Stores a new model resource and returns its generated identifier. With "
                    + "enforceUniqueFileId a fileId that already has a document is refused with 409.")
    @ApiResponse(responseCode = "201", description = "Model stored; identifier returned.")
    @ApiResponse(responseCode = "400", description = "Request payload failed validation.")
    @ApiResponse(responseCode = "409", description = "enforceUniqueFileId was set and the fileId already exists.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public ModelIdentifierResponse insert(@Valid @RequestBody InsertModelRequest request) {
        return ModelIdentifierResponse.from(
                modelRepository.insert(request.toModelReference(), request.enforcesUniqueFileId()));
    }

    @GetMapping("/{uid}")
    @Operation(
            summary = "One stored model document",
            description = "Returns the document metadata and whether a source document was captured for it.")
    @ApiResponse(responseCode = "200", description = "The stored document.")
    @ApiResponse(responseCode = "404", description = "No document with the given uid exists.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public ModelDocumentResponse document(
            @Parameter(description = "Unique identifier of the document; a numeric legacy uid is accepted.")
                    @PathVariable("uid")
                    String uid) {
        return modelRepository
                .findDocument(uid)
                .map(ModelDocumentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("model document not found"));
    }

    @GetMapping("/{uid}/document")
    @Operation(
            summary = "Download the stored source document",
            description = "Returns the document exactly as it was indexed. The truncation headers say "
                    + "whether the stored copy is shorter than the source and how large the source was.")
    @ApiResponse(responseCode = "200", description = "The stored source document.")
    @ApiResponse(responseCode = "404", description = "No document with the given uid, or it has no stored content.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public ResponseEntity<byte[]> download(
            @Parameter(description = "Unique identifier of the document; a numeric legacy uid is accepted.")
                    @PathVariable("uid")
                    String uid) {
        var document = modelRepository
                .findDocument(uid)
                .orElseThrow(() -> new ResourceNotFoundException("model document not found"));
        if (!document.hasContent()) {
            throw new ResourceNotFoundException("model document has no stored content");
        }
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(document.fileId(), StandardCharsets.UTF_8)
                .build());
        headers.set("X-Masymos-Document-Storage-Encoding", document.encoding());
        headers.set("X-Masymos-Document-Original-Bytes", Long.toString(document.originalBytes()));
        headers.set("X-Masymos-Document-Truncated", Boolean.toString(document.truncated()));
        return new ResponseEntity<>(document.contentBytes(), headers, HttpStatus.OK);
    }

    @GetMapping("/{fileId}/latest")
    @Operation(
            summary = "Latest version of a model file",
            description = "Returns the stored version with no successor; the current version of the file.")
    @ApiResponse(responseCode = "200", description = "Latest version of the model file.")
    @ApiResponse(responseCode = "404", description = "No stored version exists for the given file identifier.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public ModelVersionResponse latest(
            @Parameter(description = "File identifier of the model.", example = "BIOMD0000000001")
                    @PathVariable("fileId")
                    String fileId) {
        return modelRepository
                .findLatestVersion(fileId)
                .map(ModelVersionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("model file not found"));
    }

    @GetMapping("/{fileId}/versions")
    @Operation(
            summary = "All versions of a model file",
            description = "Returns every stored version of the file, oldest first.")
    @ApiResponse(responseCode = "200", description = "All stored versions of the model file, possibly empty.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public List<ModelVersionResponse> versions(
            @Parameter(description = "File identifier of the model.", example = "BIOMD0000000001")
                    @PathVariable("fileId")
                    String fileId) {
        return modelRepository.findVersions(fileId).stream().map(ModelVersionResponse::from).toList();
    }

    @GetMapping("/{fileId}/versions/{versionId}")
    @Operation(
            summary = "One version of a model file",
            description = "Returns the stored version carrying the given external version label.")
    @ApiResponse(responseCode = "200", description = "The requested version of the model file.")
    @ApiResponse(responseCode = "404", description = "No stored version carries that label for the given file.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public ModelVersionResponse version(
            @Parameter(description = "File identifier of the model.", example = "BIOMD0000000001")
                    @PathVariable("fileId")
                    String fileId,
            @Parameter(description = "External version label of the model.", example = "2014-01-01")
                    @PathVariable("versionId")
                    String versionId) {
        return modelRepository
                .findVersion(fileId, versionId)
                .map(ModelVersionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("model version not found"));
    }

    @DeleteMapping("/{uid}")
    @Operation(
            summary = "Delete one model document",
            description = "Removes the document with every node it owns, relinks the version chain and "
                    + "purges annotation nodes that lost their last reference. The optional fileId and "
                    + "versionId are confirmation guards: when given and not matching, nothing is deleted.")
    @ApiResponse(responseCode = "200", description = "Document deleted; the response reports what was removed.")
    @ApiResponse(responseCode = "404", description = "No document with the given uid exists.")
    @ApiResponse(responseCode = "409", description = "A confirmation parameter does not match the stored document.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public ModelDeletionResponse delete(
            @Parameter(description = "Unique identifier of the document; a numeric legacy uid is accepted.")
                    @PathVariable("uid")
                    String uid,
            @Parameter(description = "Confirmation: the document must carry this fileId.")
                    @RequestParam(name = "fileId", required = false)
                    String fileId,
            @Parameter(description = "Confirmation: the document must carry this version label.")
                    @RequestParam(name = "versionId", required = false)
                    String versionId) {
        return modelRepository
                .deleteByUid(uid, fileId, versionId)
                .map(ModelDeletionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("model not found"));
    }

    @DeleteMapping("/by-file/{fileId}")
    @Operation(
            summary = "Delete every version of a model file",
            description = "Removes every stored document with the given fileId, with the same node cleanup "
                    + "as a single delete (legacy delete_model_by_fileid).")
    @ApiResponse(responseCode = "200", description = "Versions deleted; the response reports what was removed.")
    @ApiResponse(responseCode = "404", description = "No document carries the given file identifier.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public ModelDeletionResponse deleteFile(
            @Parameter(description = "File identifier of the model.", example = "BIOMD0000000001")
                    @PathVariable("fileId")
                    String fileId) {
        return modelRepository
                .deleteByFileId(fileId)
                .map(ModelDeletionResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("model file not found"));
    }
}

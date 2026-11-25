package org.masymos.service.api;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.masymos.core.model.ModelConflictException;
import org.masymos.service.api.dto.SourceOutcomeResponse;
import org.masymos.service.indexing.PartialIndexException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one producer of an error response (task T-017).
 *
 * <p>Every {@code type} URI the API documents is set here, and a controller refuses by throwing one of
 * these types rather than by returning a {@code ProblemDetail} of its own: two producers is how the
 * documented shape silently becomes a second one. Framework exceptions this advice does not map
 * ({@code 405}, {@code 415}, {@code 406}) are problem details too, because {@code
 * spring.mvc.problemdetails.enabled} is {@code true} in {@code application.yml} (decision D4).
 *
 * <p>Two details are deliberately constant. A storage outage answers "the graph store is not
 * available" whatever the backend said, because a driver message can name a bolt URI and this response
 * is readable without a credential unless `masymos.security.shared-secret` is set (finding A04-03); the
 * real message is logged instead of published. And a
 * write failure is told apart from a source that could not be read by its type, {@link
 * WriteFailedException}, not by its text (finding A04-02).
 *
 * <p>A request refused by {@code SharedSecretFilter} before a handler exists is rendered here too: the
 * filter resolves {@link UnauthenticatedRequestException} through this advice instead of writing a body
 * of its own, so "one producer of an error response" still holds - the refusal carries the documented
 * {@code 401 unauthenticated} type rather than a shape invented at the filter (decision 48, finding
 * {@code C04-3}).
 *
 * <p>A {@link PartialIndexException} carries data rather than only a message: an index run is not atomic
 * across its sources, so its {@code outcomes} property names what every source did - including the
 * documents that were written before the run stopped (decision 49, finding {@code GAP02-002b}).
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String STORAGE_UNAVAILABLE_DETAIL = "the graph store is not available";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed"
        );
        problem.setType(URI.create("https://masymos.org/problems/validation-error"));
        problem.setTitle("Validation failed");
        problem.setProperty("errors", exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldViolation)
                .toList());
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/not-found"));
        problem.setTitle("Resource not found");
        return problem;
    }

    @ExceptionHandler(ModelConflictException.class)
    ProblemDetail handleConflict(ModelConflictException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/model-conflict"));
        problem.setTitle("Model conflict");
        return problem;
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    ProblemDetail handleProviderUnavailable(ProviderUnavailableException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/provider-unavailable"));
        problem.setTitle("No ontology term provider available");
        problem.setProperty("missingVariables", exception.missingVariables());
        return problem;
    }

    @ExceptionHandler(StorageUnavailableException.class)
    ProblemDetail handleStorageUnavailable(StorageUnavailableException exception) {
        LOGGER.warn("Storage is unavailable: {}", exception.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, STORAGE_UNAVAILABLE_DETAIL);
        problem.setType(URI.create("https://masymos.org/problems/storage-unavailable"));
        problem.setTitle("Storage unavailable");
        return problem;
    }

    @ExceptionHandler(UnauthenticatedRequestException.class)
    ProblemDetail handleUnauthenticated(UnauthenticatedRequestException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/unauthenticated"));
        problem.setTitle("Unauthenticated");
        return problem;
    }

    @ExceptionHandler(PartialIndexException.class)
    ProblemDetail handlePartialIndex(PartialIndexException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/partial-index"));
        problem.setTitle("Index run incomplete");
        problem.setProperty("outcomes", exception.outcomes().stream()
                .map(SourceOutcomeResponse::from)
                .toList());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/invalid-argument"));
        problem.setTitle("Invalid argument");
        return problem;
    }

    /**
     * A failed write answers {@code 500 write-failed}. It is registered before the {@code IOException}
     * branch by being the more specific type, so the two causes of an {@code IOException} - a source
     * that cannot be read, a target that cannot be written - no longer share one status.
     */
    @ExceptionHandler(WriteFailedException.class)
    ProblemDetail handleWriteFailed(WriteFailedException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/write-failed"));
        problem.setTitle("Write failed");
        return problem;
    }

    @ExceptionHandler(IOException.class)
    ProblemDetail handleIo(IOException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/model-source-unavailable"));
        problem.setTitle("Model source unavailable");
        return problem;
    }

    private FieldViolation toFieldViolation(FieldError error) {
        return new FieldViolation(error.getField(), List.of(error.getDefaultMessage()));
    }

    public record FieldViolation(String field, List<String> messages) {
    }
}

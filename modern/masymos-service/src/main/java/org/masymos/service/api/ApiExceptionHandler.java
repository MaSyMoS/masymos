package org.masymos.service.api;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.masymos.core.model.ModelConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
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

    @ExceptionHandler(StorageUnavailableException.class)    ProblemDetail handleStorageUnavailable(StorageUnavailableException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/storage-unavailable"));
        problem.setTitle("Storage unavailable");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setType(URI.create("https://masymos.org/problems/invalid-argument"));
        problem.setTitle("Invalid argument");
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

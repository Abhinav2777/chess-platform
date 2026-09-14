package com.chessplatform.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates exceptions into RFC 7807 {@code application/problem+json}.
 *
 * <h2>Why a standard format</h2>
 *
 * <p>Every API invents an error shape, and clients end up writing a parser per service.
 * RFC 7807 is that shape, already specified, with first-class Spring support via
 * {@link ProblemDetail}. Adopting it costs nothing and means the frontend has one code
 * path for failures.
 *
 * <h2>What leaves the building</h2>
 *
 * <p>Domain exceptions carry messages written for users, so they are returned verbatim.
 * Everything else returns a fixed string. An exception message can contain a SQL
 * fragment, a file path, a class name or an internal hostname — free reconnaissance, and
 * a stack trace in a 500 body is the classic way an attacker learns the stack.
 *
 * <p>Log levels follow the same split: domain errors are expected outcomes and log at
 * DEBUG, because a wrong password is not an incident and a thousand of them per hour
 * should not look like one. Unexpected exceptions log at ERROR with the full trace and a
 * correlation id the user can quote.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_PREFIX = "https://chess-platform.dev/errors/";

    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException exception, HttpServletRequest request) {
        HttpStatus status = statusFor(exception);
        log.debug("Domain error {} on {} {}: {}",
                exception.code(), request.getMethod(), request.getRequestURI(),
                exception.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setTitle(titleFor(status));
        problem.setType(URI.create(TYPE_PREFIX + exception.code().name().toLowerCase()));
        // The machine-readable discriminator. Clients branch on this, never on `detail`,
        // so error wording can change without breaking them.
        problem.setProperty("code", exception.code().name());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Bean-validation failures, reported per field so a form can highlight the offending
     * input rather than showing one generic message above everything.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new HashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setTitle("Validation failed");
        problem.setType(URI.create(TYPE_PREFIX + "validation_failed"));
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty("errors", fieldErrors);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        // Correlates the opaque client-facing response with the full server-side trace.
        String incidentId = java.util.UUID.randomUUID().toString();
        log.error("Unhandled exception [{}] on {} {}",
                incidentId, request.getMethod(), request.getRequestURI(), exception);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Please try again.");
        problem.setTitle("Internal error");
        problem.setType(URI.create(TYPE_PREFIX + "internal"));
        problem.setProperty("incidentId", incidentId);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private static HttpStatus statusFor(DomainException exception) {
        return switch (exception) {
            case DomainException.Conflict ignored -> HttpStatus.CONFLICT;
            case DomainException.NotFound ignored -> HttpStatus.NOT_FOUND;
            case DomainException.Unauthorized ignored -> HttpStatus.UNAUTHORIZED;
            case DomainException.Rejected ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String titleFor(HttpStatus status) {
        return status.getReasonPhrase();
    }
}

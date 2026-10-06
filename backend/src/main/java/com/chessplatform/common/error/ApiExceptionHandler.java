package com.chessplatform.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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
 *
 * <h2>Why it extends {@link ResponseEntityExceptionHandler}</h2>
 *
 * <p>Spring MVC's own exceptions — malformed JSON, a path variable of the wrong type, an
 * unsupported method, a missing static file — already know their status. The base class maps
 * each to a {@code ProblemDetail} with it. Without it, the {@code Exception} catch-all below
 * caught them all: every client mistake was a 500 with an ERROR log (found in Phase 7.1,
 * {@code ClientErrorStatusIntegrationTest}). Spring picks the most specific handler, so the
 * catch-all now sees only what is genuinely unexpected.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

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
     * 429 with {@code Retry-After}. A separate handler only because the header needs a
     * {@code ResponseEntity}; the body is the same problem+json as every other domain error.
     */
    @ExceptionHandler(DomainException.RateLimited.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(DomainException.RateLimited exception,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds(exception.retryAfter()))
                .body(handleDomain(exception, request));
    }

    /**
     * 503, with {@code Retry-After} when the thrower knows a useful wait (load shedding does;
     * an open circuit to a dependency may not).
     */
    @ExceptionHandler(DomainException.Unavailable.class)
    public ResponseEntity<ProblemDetail> handleUnavailable(DomainException.Unavailable exception,
                                                          HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        exception.retryAfter().ifPresent(wait ->
                response.header(HttpHeaders.RETRY_AFTER, retryAfterSeconds(wait)));
        return response.body(handleDomain(exception, request));
    }

    /** Whole seconds, rounded up: rounding down would tell a client to retry too early. */
    private static String retryAfterSeconds(java.time.Duration wait) {
        return Long.toString(Math.max(1, (wait.toMillis() + 999) / 1000));
    }

    /**
     * Bean-validation failures, reported per field so a form can highlight the offending
     * input rather than showing one generic message above everything. An override, not an
     * {@code @ExceptionHandler}: the base class already handles this exception, and two
     * handlers for one type is an ambiguity Spring refuses at startup.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(validationProblem(exception));
    }

    private static ProblemDetail validationProblem(MethodArgumentNotValidException exception) {
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
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        // The database being unreachable is an outage, not a bug: 503 + Retry-After, logged at
        // WARN without a stack trace — once per request, so an outage is visible, but not as an
        // ERROR that pages (10.2: a 30 s freeze was 127 ERROR lines).
        if (DatabaseUnavailable.isCause(exception)) {
            log.warn("Database unavailable on {} {}: {}", request.getMethod(), request.getRequestURI(),
                    exception.getClass().getSimpleName());
            return handleUnavailable(DatabaseUnavailable.exception(), request);
        }

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
        return ResponseEntity.internalServerError().body(problem);
    }

    private static HttpStatus statusFor(DomainException exception) {
        return switch (exception) {
            case DomainException.Conflict ignored -> HttpStatus.CONFLICT;
            case DomainException.NotFound ignored -> HttpStatus.NOT_FOUND;
            case DomainException.Unauthorized ignored -> HttpStatus.UNAUTHORIZED;
            case DomainException.Rejected ignored -> HttpStatus.UNPROCESSABLE_CONTENT;
            case DomainException.Unavailable ignored -> HttpStatus.SERVICE_UNAVAILABLE;
            case DomainException.RateLimited ignored -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static String titleFor(HttpStatus status) {
        return status.getReasonPhrase();
    }
}

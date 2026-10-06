package com.chessplatform.common.error;

/**
 * Base type for errors that are part of the domain rather than failures of the system.
 *
 * <p>The distinction matters operationally. "This username is taken" is an expected
 * outcome of a correct system and should not page anyone; a connection-pool timeout is a
 * failure and should. Separating them by type lets the exception handler map domain
 * errors to 4xx and log them at INFO, while anything else becomes a 500 logged at ERROR.
 * Conflate the two and your error rate metric measures user typos.
 *
 * <p>Extends {@link RuntimeException} so it does not pollute every method signature
 * between the domain and the controller. That is a deliberate trade: checked exceptions
 * would force callers to acknowledge these cases, but in practice produce wrapping
 * boilerplate at every layer and get swallowed.
 *
 * <p>Suppression and stack traces are disabled. These are thrown on expected paths —
 * a bot hammering registration with taken usernames would otherwise make the JVM fill
 * in a stack trace thousands of times per second for information nobody reads. The
 * exception carries a code and a message; the stack adds nothing.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode code;

    protected DomainException(ErrorCode code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /** The request was well-formed but conflicts with existing state. Maps to HTTP 409. */
    public static final class Conflict extends DomainException {
        public Conflict(ErrorCode code, String message) {
            super(code, message);
        }
    }

    /** The requested entity does not exist. Maps to HTTP 404. */
    public static final class NotFound extends DomainException {
        public NotFound(ErrorCode code, String message) {
            super(code, message);
        }
    }

    /**
     * The request was understood but cannot be carried out in the current state.
     * Maps to HTTP 422.
     *
     * <p>422 rather than 400 is a real distinction, not pedantry. 400 says "I could not
     * parse that"; a client receiving it should fix how it builds the request. 422 says
     * "I understood you perfectly and the answer is no" — an illegal chess move is
     * syntactically impeccable and semantically wrong, and the client's correct response
     * is to resync its board, not to change its serialisation.
     */
    public static class Rejected extends DomainException {
        public Rejected(ErrorCode code, String message) {
            super(code, message);
        }
    }

    /**
     * A dependency this request needs is down, and nothing about the request is wrong.
     * Maps to HTTP 503, which tells a client that retrying later is the right response —
     * unlike 500, which says the server itself is broken.
     */
    public static final class Unavailable extends DomainException {
        /** A hint for {@code Retry-After}, or null when there is no useful one to give. */
        private final java.time.Duration retryAfter;

        public Unavailable(ErrorCode code, String message) {
            this(code, message, null);
        }

        public Unavailable(ErrorCode code, String message, java.time.Duration retryAfter) {
            super(code, message);
            this.retryAfter = retryAfter;
        }

        public java.util.Optional<java.time.Duration> retryAfter() {
            return java.util.Optional.ofNullable(retryAfter);
        }
    }

    /**
     * Too many requests from this subject. Maps to HTTP 429 with {@code Retry-After}.
     * Carries the wait, so a well-behaved client can back off precisely rather than guess.
     */
    public static final class RateLimited extends DomainException {
        private final java.time.Duration retryAfter;

        public RateLimited(String message, java.time.Duration retryAfter) {
            super(ErrorCode.RATE_LIMITED, message);
            this.retryAfter = retryAfter;
        }

        public java.time.Duration retryAfter() {
            return retryAfter;
        }
    }

    /**
     * Authentication failed. Maps to HTTP 401.
     *
     * <p>Callers must not distinguish "no such user" from "wrong password" in the
     * message they return — see {@code UserAuthenticator}.
     */
    public static final class Unauthorized extends DomainException {
        public Unauthorized(ErrorCode code, String message) {
            super(code, message);
        }
    }
}

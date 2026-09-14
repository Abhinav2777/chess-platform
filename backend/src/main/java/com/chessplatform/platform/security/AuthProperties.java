package com.chessplatform.platform.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Authentication configuration.
 *
 * <p>Validated at startup, so a missing secret or nonsensical TTL is a boot failure
 * rather than a surprise at the first login. Configuration errors should never be
 * discovered by a user.
 *
 * <h2>Why the durations are checked in the constructor, not with {@code @Positive}</h2>
 *
 * <p>Bean Validation's {@code @Positive} only has validators for numeric types —
 * {@code BigDecimal}, {@code BigInteger} and the primitive number types. Applying it to a
 * {@link Duration} does not fail at compile time; it fails at startup with
 * {@code HV000030: No validator could be found for constraint}. Spring Boot ships
 * {@code @DurationUnit} for *conversion* but no duration constraint.
 *
 * <p>So the checks live in the compact constructor. That also gives a message naming the
 * offending property, which is more useful than a generic constraint violation.
 *
 * @param jwtSecret       HMAC key. HS256 requires at least 256 bits (32 bytes); anything
 *                        shorter is rejected by Nimbus at runtime, so we fail earlier and
 *                        more clearly here. From AWS Secrets Manager in production.
 * @param accessTokenTtl  Short by design. A stolen access token cannot be revoked
 *                        (ADR-009), so its lifetime *is* the exposure window.
 * @param refreshTokenTtl How long a user stays signed in without re-entering a password.
 */
@ConfigurationProperties(prefix = "chess.auth")
@Validated
public record AuthProperties(
        @NotBlank String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {

    private static final int MIN_SECRET_BYTES = 32;

    public AuthProperties {
        if (jwtSecret != null) {
            // UTF_8 explicitly, matching JwtService's key derivation. Relying on the
            // platform default charset would let this check disagree with the actual key
            // length on a non-UTF-8 JVM — a validation that passes while the thing it
            // validates is wrong.
            int bytes = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < MIN_SECRET_BYTES) {
                throw new IllegalArgumentException(
                        "chess.auth.jwt-secret must be at least " + MIN_SECRET_BYTES
                                + " bytes for HS256; got " + bytes);
            }
        }
        requirePositive(accessTokenTtl, "chess.auth.access-token-ttl");
        requirePositive(refreshTokenTtl, "chess.auth.refresh-token-ttl");
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    property + " must be a positive duration (e.g. 15m, 14d); got " + value);
        }
    }
}
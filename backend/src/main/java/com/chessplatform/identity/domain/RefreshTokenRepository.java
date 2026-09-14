package com.chessplatform.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomically claims a token for rotation.
     *
     * <p>This is the concurrency control for refresh, and it is deliberately a
     * conditional UPDATE rather than a read-check-write in Java. Two requests carrying
     * the same refresh token — a double-clicked retry, or two browser tabs — would both
     * pass an {@code if (!token.isUsed())} check before either wrote, and both would mint
     * a new token pair. One of those pairs then belongs to nobody and the other looks
     * like theft.
     *
     * <p>{@code WHERE used_at IS NULL} makes the database decide. Exactly one caller sees
     * a row count of 1; every other sees 0 and is rejected. Same principle as the
     * registration race in {@code UserRegistrar} and move idempotency in ADR-005: the
     * invariant is enforced where writes are serialised, not where they are convenient.
     *
     * @return 1 if this caller claimed the token, 0 if someone else already had
     */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL")
    int claimForRotation(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Revokes an entire rotation lineage. Called when a used token is presented again,
     * which means the token leaked: we cannot tell the thief from the legitimate holder,
     * so both are logged out and the user re-authenticates.
     */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
           + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}

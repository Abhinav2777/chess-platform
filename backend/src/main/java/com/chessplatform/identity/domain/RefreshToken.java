package com.chessplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A rotating refresh token. Stores only the SHA-256 of the token; the raw value lives
 * exclusively in the client's cookie, so a database dump yields no usable sessions.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
    }

    private RefreshToken(UUID id, UUID userId, String tokenHash, UUID familyId,
                         Instant issuedAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public static RefreshToken issue(UUID id, UUID userId, String tokenHash, UUID familyId,
                                     Instant issuedAt, Instant expiresAt) {
        return new RefreshToken(id, userId, tokenHash, familyId, issuedAt, expiresAt);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID familyId() {
        return familyId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Valid means: not yet rotated, not revoked, not expired. All three must hold. */
    public boolean isUsableAt(Instant now) {
        return !isUsed() && !isRevoked() && !isExpiredAt(now);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RefreshToken token && Objects.equals(id, token.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /** Never prints the hash. */
    @Override
    public String toString() {
        return "RefreshToken[id=%s, user=%s, family=%s]".formatted(id, userId, familyId);
    }
}

package com.chessplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A registered player.
 *
 * <p>Maps to the {@code users} table created in {@code V1__baseline.sql}. Flyway owns
 * the schema; this class must agree with it or the application refuses to start
 * ({@code spring.jpa.hibernate.ddl-auto=validate}).
 *
 * <h2>Why this is a class and not a record</h2>
 *
 * <p>JPA requires a no-arg constructor and mutable fields for its proxying and dirty
 * checking, so entities cannot be records. Records are used everywhere state crosses a
 * boundary — DTOs, events, the WebSocket envelope — but the persistence layer is the one
 * place the framework dictates the shape.
 *
 * <h2>Why the ID is a plain UUID</h2>
 *
 * <p>A typed wrapper ({@code record UserId(UUID value)}) would make it impossible to pass
 * a game ID where a user ID is expected. Considered and rejected for now: it needs an
 * {@code AttributeConverter} and a Jackson serialiser per type, and with a handful of
 * entities whose IDs always travel through explicitly-named parameters, the mixup risk
 * doesn't justify the ceremony. Revisit if IDs start flowing through generic plumbing.
 *
 * <h2>Equality</h2>
 *
 * <p>Based on the identifier alone, not on business fields. IDs are assigned before
 * persist (we generate them, the database does not), so an entity's identity is stable
 * from construction — which avoids the classic JPA trap where an entity added to a
 * {@code HashSet} before flush becomes unfindable afterwards because its hash changed.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, length = 32, updatable = false)
    private String username;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /**
     * Includes the algorithm prefix and salt, e.g. {@code {bcrypt}$2a$12$...}.
     * The plaintext password never exists as a field anywhere in this application.
     */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "rating", nullable = false)
    private int rating;

    /**
     * {@code TIMESTAMPTZ}. Mapped by {@code hibernate.type.preferred_instant_jdbc_type}
     * in {@code application.yml} — verified 2026-09-14 that the global property alone is
     * sufficient, so no per-field {@code @JdbcTypeCode} is needed here or on any future
     * timestamp.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. Not for application use. */
    protected User() {
    }

    private User(UUID id, String username, String email, String passwordHash,
                 int rating, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.rating = rating;
        this.createdAt = createdAt;
    }

    /**
     * The only way to create a user. A private constructor plus a named factory means
     * there is no path to a {@code User} with a null hash or an unassigned ID, and no
     * anaemic setter surface for callers to misuse.
     *
     * <p>Values are assumed already normalised and validated — that is
     * {@code UserRegistrar}'s job, because normalisation needs to match the lookup path
     * exactly and belongs in one place.
     */
    public static User register(UUID id, String username, String email,
                                String passwordHash, int initialRating, Instant createdAt) {
        return new User(id, username, email, passwordHash, initialRating, createdAt);
    }

    public UUID id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public int rating() {
        return rating;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof User user && Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    /**
     * Deliberately excludes the email and hash. {@code toString()} ends up in log lines,
     * exception messages, and IDE debugger tooltips; anything printed here should be
     * safe to appear in all three.
     */
    @Override
    public String toString() {
        return "User[id=%s, username=%s, rating=%d]".formatted(id, username, rating);
    }
}

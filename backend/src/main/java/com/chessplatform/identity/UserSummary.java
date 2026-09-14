package com.chessplatform.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The identity module's public view of a user.
 *
 * <p>Other modules receive this, never the {@code User} entity. That boundary is
 * enforced by ArchUnit, not convention: {@code ModuleBoundaryTest} fails the build if an
 * {@code @Entity} is referenced from outside its own module.
 *
 * <p>Two reasons it matters. A JPA entity handed across a boundary is a live, possibly
 * lazily-initialised object attached to a persistence context — the receiving module can
 * mutate it, or trip a {@code LazyInitializationException} outside the transaction.
 * And it welds every consumer to identity's storage schema, so a column rename becomes a
 * compile error in modules that have no business knowing the column exists.
 *
 * <p>No email, no hash. Consumers need a display name and a rating.
 */
public record UserSummary(UUID id, String username, int rating, Instant createdAt) {
}

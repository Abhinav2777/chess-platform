package com.chessplatform.identity.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link User}.
 *
 * <p>Lookups are by the <em>normalised</em> value. {@code UserRegistrar} lowercases both
 * username and email before storing, so these queries can be exact matches against the
 * unique indexes in {@code V1__baseline.sql} rather than {@code LOWER(...)} expressions
 * that would not use them.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);
}

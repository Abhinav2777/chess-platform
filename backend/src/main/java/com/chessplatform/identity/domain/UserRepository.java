package com.chessplatform.identity.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /**
     * {@code SELECT … FOR UPDATE}, ordered by id. The order is the point: two transactions
     * that each lock the same pair of players always take the locks in the same order, so
     * they queue instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id IN :ids ORDER BY u.id")
    List<User> lockAllById(@Param("ids") Collection<UUID> ids);
}

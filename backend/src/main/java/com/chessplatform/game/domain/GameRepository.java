package com.chessplatform.game.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GameRepository extends JpaRepository<Game, UUID> {

    /**
     * A player's games, newest first.
     *
     * <p>Hits {@code idx_games_white} and {@code idx_games_black} as a union of two index
     * scans. An {@code OR} across two columns is the one shape PostgreSQL handles well
     * here precisely because both are indexed; without those indexes this is a sequential
     * scan of every game ever played.
     *
     * <p>Offset pagination, not keyset, and deliberately so: this is a "my games" screen
     * where nobody pages past the first few, and keyset would need a compound cursor over
     * {@code (created_at, id)}. Keyset is the right default for feeds that grow while
     * being read (ARCHITECTURE.md 4.3) — this is not one.
     */
    @Query("SELECT g FROM Game g WHERE g.whitePlayerId = :playerId OR g.blackPlayerId = :playerId "
           + "ORDER BY g.createdAt DESC")
    List<Game> findByPlayer(@Param("playerId") UUID playerId, Pageable pageable);

    /**
     * Claims games whose clock has expired, for the timeout sweeper.
     *
     * <h2>{@code FOR UPDATE SKIP LOCKED} is the whole design</h2>
     *
     * <p>Several sweeper replicas run concurrently. {@code FOR UPDATE} alone would make
     * them queue behind each other on the same rows; {@code SKIP LOCKED} makes each one
     * take a disjoint batch and get on with it. <strong>The database partitions the work,
     * so no distributed lock, leader election, or coordination is needed</strong> — which
     * is the same argument as ADR-005, applied to background work rather than to moves.
     *
     * <p>Double-finalisation is impossible: a row locked by one sweeper is invisible to
     * the others until that transaction commits, by which point it is no longer ACTIVE and
     * fails the predicate.
     *
     * <p>Native, because JPQL has no {@code SKIP LOCKED}. {@code LIMIT} bounds the batch
     * so one sweep cannot hold locks on thousands of rows or run unboundedly long.
     *
     * <p>{@code now()} is evaluated by PostgreSQL, so the sweeper compares against the same
     * time authority the move pipeline uses (see {@code ServerClock}) rather than against
     * the clock of whichever host happens to run it.
     */
    @Query(value = """
            SELECT id FROM games
             WHERE status = 'ACTIVE' AND turn_deadline < now()
             ORDER BY turn_deadline
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> claimExpired(@Param("batchSize") int batchSize);

    @Query("SELECT count(g) FROM Game g WHERE g.status = com.chessplatform.game.GameStatus.ACTIVE "
           + "AND g.turnDeadline < :now")
    long countExpired(@Param("now") Instant now);
}

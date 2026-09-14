package com.chessplatform.game.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}

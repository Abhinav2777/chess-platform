package com.chessplatform.game.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MoveRepository extends JpaRepository<MoveRecord, MoveRecordId> {

    /** The idempotency lookup. Backed by {@code uq_moves_client_id}. */
    Optional<MoveRecord> findByGameIdAndClientMoveId(UUID gameId, UUID clientMoveId);

    /** A game's moves in order — a single index range scan on the primary key. */
    List<MoveRecord> findByGameIdOrderByPlyAsc(UUID gameId);

    /**
     * Positions after plies {@code [fromPly, toPly)} — the history a repetition check needs.
     *
     * <p>A range scan on {@code PRIMARY KEY (game_id, ply)}, and a projection rather than
     * entities: the check only compares FENs, so loading up to 100 managed entities into
     * the move transaction's persistence context would be pure overhead.
     */
    @Query("""
            SELECT m.fenAfter FROM MoveRecord m
             WHERE m.gameId = :gameId AND m.ply >= :fromPly AND m.ply < :toPly
            """)
    List<String> findFensBetween(@Param("gameId") UUID gameId,
                                 @Param("fromPly") int fromPly,
                                 @Param("toPly") int toPly);
}

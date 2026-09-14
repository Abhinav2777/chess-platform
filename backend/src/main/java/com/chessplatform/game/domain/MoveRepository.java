package com.chessplatform.game.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MoveRepository extends JpaRepository<MoveRecord, MoveRecordId> {

    /** The idempotency lookup. Backed by {@code uq_moves_client_id}. */
    Optional<MoveRecord> findByGameIdAndClientMoveId(UUID gameId, UUID clientMoveId);

    /** A game's moves in order — a single index range scan on the primary key. */
    List<MoveRecord> findByGameIdOrderByPlyAsc(UUID gameId);
}

package com.chessplatform.game.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link MoveRecord}: {@code (game_id, ply)}.
 *
 * <p>Field names must match the {@code @Id} fields on the entity — JPA matches by name,
 * not by position, and a mismatch fails at startup rather than compile time.
 *
 * <p>The composite key is not incidental. It is the third layer of move-safety in
 * ADR-005: even with the idempotency key and the optimistic lock both broken, the database
 * physically cannot hold two moves at the same ply of the same game.
 */
public class MoveRecordId implements Serializable {

    private UUID gameId;
    private int ply;

    protected MoveRecordId() {
    }

    public MoveRecordId(UUID gameId, int ply) {
        this.gameId = gameId;
        this.ply = ply;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MoveRecordId id
               && ply == id.ply
               && Objects.equals(gameId, id.gameId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gameId, ply);
    }
}

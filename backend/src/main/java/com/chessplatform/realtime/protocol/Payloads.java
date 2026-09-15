package com.chessplatform.realtime.protocol;

import com.chessplatform.chess.Promotion;
import com.chessplatform.chess.Side;

import java.util.List;
import java.util.UUID;

/** Payload bodies for the protocol. Records, so serialisation needs no annotations. */
public final class Payloads {

    private Payloads() {
    }

    // ---------------------------------------------------------------- inbound

    public record Auth(String token) {
    }

    public record Subscribe(UUID gameId) {
    }

    /**
     * Identical in shape to the REST move request, deliberately. One validation pipeline
     * serves both transports; a second shape would be a second place for the rules to
     * drift apart.
     */
    public record Move(UUID gameId, UUID clientMoveId, int expectedPly,
                       String from, String to, Promotion promotion) {
    }

    public record Resign(UUID gameId) {
    }

    // --------------------------------------------------------------- outbound

    public record AuthOk(UUID userId, String username) {
    }

    public record Failure(String code, String message) {
    }

    /**
     * Everything needed to render the board from cold.
     *
     * @param yourSide null when the subscriber is not a player. Spectators do not exist
     *                 yet, but the field means adding them later does not change the shape.
     */
    public record GameSnapshot(UUID gameId, String fen, int ply, Side sideToMove,
                               UUID whitePlayerId, UUID blackPlayerId,
                               Side yourSide, boolean opponentOnline,
                               String status, String result,
                               String termination, List<String> legalMoves,
                               String lastMoveUci) {
    }

    /**
     * @param legalMoves legality in the new position. Sent with the move so a client is
     *                   never holding a board it cannot play on — it has no rules engine
     *                   and cannot work them out.
     */
    public record MoveMade(UUID gameId, int ply, String uci, String san,
                           String fenAfter, Side sideToMove, List<String> legalMoves) {
    }

    public record GameFinished(UUID gameId, String result, String termination) {
    }
}

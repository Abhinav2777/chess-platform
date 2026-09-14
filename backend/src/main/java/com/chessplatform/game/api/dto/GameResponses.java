package com.chessplatform.game.api.dto;

import com.chessplatform.chess.Side;
import com.chessplatform.game.GameResult;
import com.chessplatform.game.GameStatus;
import com.chessplatform.game.GameView;
import com.chessplatform.game.Termination;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GameResponses {

    private GameResponses() {
    }

    public record GameSummary(UUID id, UUID whitePlayerId, UUID blackPlayerId,
                              GameStatus status, GameResult result, Termination termination,
                              String fen, int ply, Side sideToMove,
                              Instant createdAt, Instant finishedAt) {

        public static GameSummary from(GameView view) {
            return new GameSummary(view.id(), view.whitePlayerId(), view.blackPlayerId(),
                    view.status(), view.result(), view.termination(), view.fen(),
                    view.ply(), view.sideToMove(), view.createdAt(), view.finishedAt());
        }
    }

    public record PlayedMove(int ply, String uci, String san, Instant playedAt) {
    }

    /**
     * @param legalMoves every legal move in UCI form, so the client can highlight
     *                   destinations without reimplementing the rules. It does not make
     *                   the client authoritative — the server validates every move it
     *                   receives, because a hostile client would simply not ask.
     */
    public record GameDetail(GameSummary game, List<PlayedMove> moves, List<String> legalMoves) {
    }

    /**
     * @param replayed true when this was a retry served from the existing record rather
     *                 than a newly applied move. Surfaced so the client can distinguish
     *                 "your retry worked" from "you played again".
     */
    public record MoveAccepted(int ply, String uci, String san, String fen,
                               Side sideToMove, boolean gameOver, boolean replayed) {
    }
}

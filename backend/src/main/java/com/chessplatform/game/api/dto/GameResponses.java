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

    /**
     * @param whiteUsername may be null when the caller did not ask for names — the single
     *                      game endpoint omits them, the list endpoint supplies them.
     *                      A client that only has opaque UUIDs cannot tell one game from
     *                      another, which turns a game list into a row of identical
     *                      buttons.
     */
    public record GameSummary(UUID id, UUID whitePlayerId, UUID blackPlayerId,
                              String whiteUsername, String blackUsername,
                              GameStatus status, GameResult result, Termination termination,
                              String fen, int ply, Side sideToMove,
                              long incrementMs, long whiteMsLeft, long blackMsLeft,
                              Instant createdAt, Instant finishedAt) {

        public static GameSummary from(GameView view) {
            return withNames(view, null, null);
        }

        /**
         * Clocks are sent as of {@code now}, already adjusted for the time the side to
         * move has been spending. The client ticks from there and resyncs on the next
         * server message — it never computes elapsed time from a timestamp of its own,
         * because its clock is not the authority (ADR-006).
         */
        public static GameSummary withNames(GameView view, String white, String black) {
            Instant now = Instant.now();
            return new GameSummary(view.id(), view.whitePlayerId(), view.blackPlayerId(),
                    white, black,
                    view.status(), view.result(), view.termination(), view.fen(),
                    view.ply(), view.sideToMove(),
                    view.incrementMs(),
                    view.remainingMs(Side.WHITE, now), view.remainingMs(Side.BLACK, now),
                    view.createdAt(), view.finishedAt());
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
                               Side sideToMove, boolean gameOver, boolean replayed,
                               long whiteMsLeft, long blackMsLeft) {
    }
}

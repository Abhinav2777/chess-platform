package com.chessplatform.chess;

import java.util.Objects;

/**
 * A chess position, as a FEN string.
 *
 * <p>FEN rather than a board array because it is the entire position in ~60 bytes:
 * pieces, side to move, castling rights, en passant target, halfmove clock and fullmove
 * number. That makes it cheap to persist, cheap to send over a WebSocket, and trivially
 * comparable — three properties a richer representation would cost us for no gain, since
 * nothing in this system reasons about the board except the rules engine.
 *
 * <p><strong>FEN carries no history.</strong> A position cannot know it has occurred
 * before, so threefold repetition is decided by
 * {@link ChessRules#isThreefoldRepetition}, which is handed the earlier positions
 * explicitly. The rules engine stays stateless; the caller owns the history (ADR-015).
 */
public record Position(String fen) {

    public static final String STARTING_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public Position {
        Objects.requireNonNull(fen, "fen");
        if (fen.isBlank()) {
            throw new IllegalArgumentException("fen must not be blank");
        }
    }

    public static Position starting() {
        return new Position(STARTING_FEN);
    }

    /** Side to move, read from the FEN's second field. */
    public Side sideToMove() {
        String[] fields = fen.split(" ");
        if (fields.length < 2) {
            throw new IllegalStateException("malformed FEN: " + fen);
        }
        return Side.fromFenCode(fields[1]);
    }

    /**
     * Plies since the last capture or pawn move, read from the FEN's fifth field.
     *
     * <p>Those two kinds of move are irreversible — a captured piece never returns, a pawn
     * never moves backwards — so no position before the last one can ever recur. This
     * number is therefore the exact length of the history a repetition check has to look
     * at, which is what bounds that check to at most 100 earlier positions.
     */
    public int halfmoveClock() {
        String[] fields = fen.split(" ");
        if (fields.length < 5) {
            throw new IllegalStateException("FEN has no halfmove clock: " + fen);
        }
        return Integer.parseInt(fields[4]);
    }
}

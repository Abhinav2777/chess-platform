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
 * <p><strong>Known gap: threefold repetition.</strong> FEN carries no move history, so a
 * position reconstructed from FEN cannot know it has occurred before. Repetition draws
 * are therefore not detected. This is a deliberate consequence of statelessness, not an
 * oversight — see {@code GameOutcome} and the technical-debt register in
 * {@code PROJECT_STATE.md}.
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
     * The piece placement and rights, without the move counters.
     *
     * <p>This is the part that determines whether two positions are "the same" for
     * repetition purposes — the halfmove and fullmove counters differ every move and
     * would make every position unique. Unused today; it is the key a repetition check
     * would count on (see the Position class comment).
     */
    public String repetitionKey() {
        String[] fields = fen.split(" ");
        return String.join(" ", fields[0], fields[1], fields[2], fields[3]);
    }
}

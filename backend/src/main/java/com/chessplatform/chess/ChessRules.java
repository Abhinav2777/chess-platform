package com.chessplatform.chess;

import java.util.List;

/**
 * The chess rules the rest of the system depends on.
 *
 * <p>A port, not a facade over a library type. No chesslib class appears in any signature
 * here, so the implementation is replaceable and — more importantly — the constraint that
 * chesslib's {@code Board} is mutable and not thread-safe is enforced in exactly one place
 * rather than trusted across the codebase (ADR-002).
 *
 * <p>Implementations must be stateless and safe for concurrent use.
 */
public interface ChessRules {

    /** The standard starting position. */
    Position startingPosition();

    /**
     * Applies a move.
     *
     * @throws IllegalMoveException if the move is not legal in this position — wrong
     *                              piece, wrong side, leaves the king in check, or any
     *                              other violation
     */
    MoveResult apply(Position position, MoveIntent intent);

    /**
     * Every legal move, in UCI form.
     *
     * <p>Exists so the client can highlight destinations without reimplementing the rules.
     * It does not make the client authoritative: the server still validates every move it
     * receives, because a hostile client simply would not call this.
     */
    List<String> legalMoves(Position position);

    /** Whether the side to move has no legal move — i.e. the game has ended by rule. */
    boolean isGameOver(Position position);

    /**
     * Whether {@code current} is at least the third occurrence of the same position.
     *
     * <p>"Same" in the FIDE sense (Article 9.2.3): same pieces on the same squares, same
     * side to move, same castling rights, and the same en-passant possibilities. Move
     * counters are ignored. An en-passant square counts only when a legal en-passant
     * capture exists — a FEN written after every double pawn push would otherwise make
     * identical positions look different and miss the draw.
     *
     * <p>History is a parameter rather than state, so implementations stay stateless. The
     * caller decides how much history is relevant; everything before the last capture or
     * pawn move is harmless to include and pointless to fetch (see
     * {@link Position#halfmoveClock()}).
     *
     * @param earlier positions reached earlier in the same game, in any order, not
     *                including {@code current}
     */
    boolean isThreefoldRepetition(Position current, List<Position> earlier);
}

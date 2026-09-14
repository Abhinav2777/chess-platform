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
}

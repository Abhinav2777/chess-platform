package com.chessplatform.chess;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;

/**
 * The requested move is not legal in the given position.
 *
 * <p>An expected outcome, not a system failure — clients send illegal moves through lag,
 * stale board state, or simple mistakes. It extends {@link DomainException.Rejected}, so
 * it maps to 422 and logs at DEBUG rather than paging anyone.
 *
 * <p>The message never explains <em>why</em> the move is illegal. Doing so would leak the
 * engine's reasoning to a client that should be deriving legality from the position it
 * already has, and the distinction is not useful to an honest client anyway.
 */
public class IllegalMoveException extends DomainException.Rejected {

    public IllegalMoveException(String uci) {
        super(ErrorCode.ILLEGAL_MOVE, "That move is not legal in this position: " + uci);
    }
}

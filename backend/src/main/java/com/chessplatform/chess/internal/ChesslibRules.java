package com.chessplatform.chess.internal;

import com.chessplatform.chess.ChessRules;
import com.chessplatform.chess.GameOutcome;
import com.chessplatform.chess.IllegalMoveException;
import com.chessplatform.chess.MoveIntent;
import com.chessplatform.chess.MoveResult;
import com.chessplatform.chess.Position;
import com.chessplatform.chess.Side;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveList;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ChessRules} over bhlangonijr/chesslib.
 *
 * <h2>The rule this class exists to enforce</h2>
 *
 * <p>chesslib's {@code Board} is mutable and not thread-safe. Every method here therefore
 * constructs a board from the supplied FEN, uses it, and discards it. <strong>No
 * {@code Board} is ever a field, cached, or shared.</strong> That is the whole reason
 * ADR-002 wrapped the library instead of calling it directly: the constraint is enforced
 * in one file rather than trusted everywhere.
 *
 * <p>The cost is parsing a ~60 byte FEN per call, which is microseconds — far cheaper than
 * a shared-mutable-state bug in a system where two players hit the same game concurrently.
 *
 * <p>This class is stateless and safe for concurrent use.
 */
@Component
public class ChesslibRules implements ChessRules {

    /** Half-moves without a capture or pawn move before the fifty-move rule applies. */
    private static final int FIFTY_MOVE_PLIES = 100;

    @Override
    public Position startingPosition() {
        return Position.starting();
    }

    @Override
    public MoveResult apply(Position position, MoveIntent intent) {
        Board board = boardFrom(position);
        String uci = intent.toUci();

        Move move = new Move(uci, board.getSideToMove());

        // Membership in legalMoves() rather than isMoveLegal(). Move equality accounts for
        // the promotion piece, so "e7e8q" and "e7e8n" are correctly distinguished — which
        // a from/to comparison would not be.
        if (!board.legalMoves().contains(move)) {
            throw new IllegalMoveException(uci);
        }

        // SAN must be produced from the position BEFORE the move, since notation depends
        // on what else could have moved there (Nbd2 vs Nfd2) and on whether the move gives
        // check. Captured here, before the board advances.
        String san = toSan(position, move);

        board.doMove(move);

        return new MoveResult(
                uci,
                san,
                new Position(board.getFen()),
                sideOf(board),
                outcomeOf(board));
    }

    @Override
    public List<String> legalMoves(Position position) {
        return boardFrom(position).legalMoves().stream()
                .map(Move::toString)
                .toList();
    }

    @Override
    public boolean isGameOver(Position position) {
        Board board = boardFrom(position);
        return board.isMated() || board.isStaleMate() || board.isDraw();
    }

    @Override
    public boolean isThreefoldRepetition(Position current, List<Position> earlier) {
        String key = repetitionKey(current);
        long previousOccurrences = earlier.stream()
                .filter(position -> key.equals(repetitionKey(position)))
                .limit(2)
                .count();
        return previousOccurrences >= 2;
    }

    /**
     * What makes two positions "the same" for repetition: placement, side to move,
     * castling rights, and the en-passant square only if a legal en-passant capture
     * exists. Move counters are dropped — they differ every move.
     *
     * <p>The en-passant normalisation is not pedantry. chesslib writes the square after
     * <em>every</em> double pawn push ({@code e3} after 1.e4, with no black pawn anywhere
     * near), so without it the position straight after a double push never matches the
     * same position reached later, and a genuine threefold goes undetected.
     *
     * <p>Cheap in practice: only a position whose FEN has an en-passant square needs a
     * board and move generation, and that is at most the first position of any
     * repetition window (a pawn move resets the window).
     */
    private static String repetitionKey(Position position) {
        String[] fields = position.fen().split(" ");
        if (fields.length < 4) {
            throw new IllegalStateException("malformed FEN: " + position.fen());
        }
        String enPassant = fields[3];
        if (!"-".equals(enPassant) && !hasLegalEnPassantCapture(position, enPassant)) {
            enPassant = "-";
        }
        return String.join(" ", fields[0], fields[1], fields[2], enPassant);
    }

    private static boolean hasLegalEnPassantCapture(Position position, String target) {
        Board board = boardFrom(position);
        return board.legalMoves().stream()
                .anyMatch(move -> move.getTo().name().equalsIgnoreCase(target)
                                  && board.getPiece(move.getFrom()).getPieceType() == PieceType.PAWN);
    }

    private static Board boardFrom(Position position) {
        Board board = new Board();
        // No event listeners are registered, so publishing them is pure overhead. The
        // library's own perft harness does the same.
        board.setEnableEvents(false);
        board.loadFromFen(position.fen());
        return board;
    }

    private static Side sideOf(Board board) {
        return board.getSideToMove() == com.github.bhlangonijr.chesslib.Side.WHITE
                ? Side.WHITE
                : Side.BLACK;
    }

    /**
     * Determines how the game ended, in a deliberate order.
     *
     * <p>Checkmate and stalemate first, because both are "no legal move" and only the
     * check status distinguishes them. Then the specific draw reasons, because
     * {@code isDraw()} collapses all of them into one boolean and we want to record
     * <em>why</em> a game was drawn — a game history that says only "draw" is a worse
     * artifact.
     *
     * <p>Repetition is deliberately absent. A board loaded from FEN has no history, so
     * {@code isRepetition()} is always false here; the game module decides repetition
     * from the move log via {@link #isThreefoldRepetition}. An earlier version mapped any
     * leftover {@code isDraw()} to {@code DRAW_REPETITION} — unreachable, since every
     * other draw is tested above, but a label that would have been wrong the day it fired.
     */
    private static GameOutcome outcomeOf(Board board) {
        if (board.isMated()) {
            return GameOutcome.CHECKMATE;
        }
        if (board.isStaleMate()) {
            return GameOutcome.STALEMATE;
        }
        if (board.isInsufficientMaterial()) {
            return GameOutcome.DRAW_INSUFFICIENT_MATERIAL;
        }
        if (board.getHalfMoveCounter() >= FIFTY_MOVE_PLIES) {
            return GameOutcome.DRAW_FIFTY_MOVE;
        }
        return GameOutcome.IN_PROGRESS;
    }

    /**
     * Renders a move in Standard Algebraic Notation.
     *
     * <p>SAN is position-dependent — it disambiguates between pieces that could reach the
     * same square and appends check and mate markers — so it cannot be derived from the
     * move alone. chesslib produces it through {@link MoveList}, which is seeded with the
     * position the move is played from.
     *
     * <p>Failure is not silently tolerated. {@code moves.san} is {@code NOT NULL} and a
     * game history with a wrong or placeholder notation is worse than no history, so a
     * conversion problem surfaces as an error rather than a fallback value.
     */
    private static String toSan(Position position, Move move) {
        try {
            MoveList moveList = new MoveList(position.fen());
            moveList.add(move);
            return moveList.toSanArray()[0];
        } catch (RuntimeException conversionFailed) {
            throw new IllegalStateException(
                    "Could not render SAN for legal move " + move + " in " + position.fen(),
                    conversionFailed);
        }
    }
}

package com.chessplatform.chess;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies chesslib's move generation against published perft results.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>ADR-002 chose a library over writing a rules engine. That decision is only sound if
 * the library is correct, and "it has stars on GitHub" is not evidence. Perft is: it
 * counts every leaf node of the move tree to a given depth, so a single missing or extra
 * move anywhere — a castling right not revoked, an en passant capture wrongly allowed,
 * a pin not respected — changes the total. The reference numbers below are published and
 * agreed across every serious chess engine.
 *
 * <p>The positions are the standard set, each chosen to stress a different corner:
 * castling and pins (Kiwipete), en passant and promotion edge cases (positions 3–5). A
 * generator that passes all five is correct in practice.
 *
 * <p>This is also the test that would catch a bad library <em>upgrade</em>. Without it,
 * a subtly wrong en passant rule produces games that look entirely normal and are invalid.
 *
 * <h2>Why it uses chesslib directly</h2>
 *
 * <p>Perft needs {@code undoMove}, which {@link ChessRules} deliberately does not expose —
 * nothing in the application ever takes a move back. This test verifies the library
 * beneath the port, so it is the one place outside the adapter that touches chesslib.
 */
@DisplayName("Perft — move generation correctness")
class PerftTest {

    private static final String INITIAL =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    private static final String KIWIPETE =
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";
    private static final String POSITION_3 =
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1";
    private static final String POSITION_4 =
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1";
    private static final String POSITION_5 =
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8";

    @ParameterizedTest(name = "{2} depth {1} -> {3} nodes")
    @CsvSource({
            // Starting position — the baseline every engine agrees on.
            INITIAL + ", 1, initial, 20",
            INITIAL + ", 2, initial, 400",
            INITIAL + ", 3, initial, 8902",
            INITIAL + ", 4, initial, 197281",

            // Kiwipete — castling both sides, pins, and a dense middlegame.
            KIWIPETE + ", 1, kiwipete, 48",
            KIWIPETE + ", 2, kiwipete, 2039",
            KIWIPETE + ", 3, kiwipete, 97862",

            // Position 3 — en passant and rook endgame subtleties.
            POSITION_3 + ", 1, position-3, 14",
            POSITION_3 + ", 2, position-3, 191",
            POSITION_3 + ", 3, position-3, 2812",
            POSITION_3 + ", 4, position-3, 43238",

            // Position 4 — promotion, including underpromotion.
            POSITION_4 + ", 1, position-4, 6",
            POSITION_4 + ", 2, position-4, 264",
            POSITION_4 + ", 3, position-4, 9467",

            // Position 5 — a known trap for castling-rights bugs.
            POSITION_5 + ", 1, position-5, 44",
            POSITION_5 + ", 2, position-5, 1486",
            POSITION_5 + ", 3, position-5, 62379"
    })
    void matchesPublishedNodeCounts(String fen, int depth, String name, long expectedNodes) {
        Board board = new Board();
        board.setEnableEvents(false);
        board.loadFromFen(fen);

        assertThat(perft(board, depth))
                .as("perft(%s, %d)", name, depth)
                .isEqualTo(expectedNodes);
    }

    /**
     * Also asserts board consistency, implicitly. Every {@code doMove} is paired with an
     * {@code undoMove}, so if the library left the board in a wrong state after undoing,
     * the counts at higher depths would diverge.
     */
    private static long perft(Board board, int depth) {
        if (depth == 0) {
            return 1;
        }
        long nodes = 0;
        List<Move> moves = board.legalMoves();
        for (Move move : moves) {
            board.doMove(move);
            nodes += perft(board, depth - 1);
            board.undoMove();
        }
        return nodes;
    }
}

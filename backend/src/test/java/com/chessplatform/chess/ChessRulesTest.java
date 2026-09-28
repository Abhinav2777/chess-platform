package com.chessplatform.chess;

import com.chessplatform.chess.internal.ChesslibRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the port's contract, not chesslib's internals — {@link PerftTest} covers those.
 *
 * <p>Pure unit tests: no Spring, no database, no containers. They run in milliseconds,
 * which is deliberate. The rules engine is the component most likely to be exercised
 * while iterating, and a feedback loop that needs Docker is one nobody uses.
 */
@DisplayName("ChessRules")
class ChessRulesTest {

    private final ChessRules rules = new ChesslibRules();

    @Nested
    @DisplayName("legality")
    class Legality {

        @Test
        @DisplayName("accepts a legal opening move and advances the side to move")
        void appliesLegalMove() {
            MoveResult result = rules.apply(rules.startingPosition(), MoveIntent.of("e2", "e4"));

            assertThat(result.uci()).isEqualTo("e2e4");
            assertThat(result.san()).isEqualTo("e4");
            assertThat(result.sideToMove()).isEqualTo(Side.BLACK);
            assertThat(result.outcome()).isEqualTo(GameOutcome.IN_PROGRESS);
            assertThat(result.positionAfter().fen()).startsWith("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b");
        }

        @Test
        @DisplayName("rejects a move the piece cannot make")
        void rejectsImpossibleMove() {
            assertThatThrownBy(() -> rules.apply(rules.startingPosition(), MoveIntent.of("e2", "e5")))
                    .isInstanceOf(IllegalMoveException.class);
        }

        /**
         * Turn enforcement lives in the rules, not only in the game module. Defence in
         * depth: a bug in the turn check upstream still cannot produce an invalid game.
         */
        @Test
        @DisplayName("rejects a move by the side that is not to move")
        void rejectsWrongSide() {
            assertThatThrownBy(() -> rules.apply(rules.startingPosition(), MoveIntent.of("b8", "c6")))
                    .isInstanceOf(IllegalMoveException.class);
        }

        @Test
        @DisplayName("rejects a move that would leave the king in check")
        void rejectsMoveIntoCheck() {
            // White king e1, black rook e8: the e2 knight is pinned to the king.
            Position pinned = new Position("4r3/8/8/8/8/8/4N3/4K3 w - - 0 1");

            assertThatThrownBy(() -> rules.apply(pinned, MoveIntent.of("e2", "c3")))
                    .isInstanceOf(IllegalMoveException.class);
        }

        @Test
        @DisplayName("lists exactly twenty legal opening moves")
        void listsLegalMoves() {
            assertThat(rules.legalMoves(rules.startingPosition()))
                    .hasSize(20)
                    .contains("e2e4", "g1f3");
        }
    }

    @Nested
    @DisplayName("special moves")
    class SpecialMoves {

        @Test
        @DisplayName("castles kingside and renders it as O-O")
        void castlesKingside() {
            Position position = new Position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");

            MoveResult result = rules.apply(position, MoveIntent.of("e1", "g1"));

            assertThat(result.san()).isEqualTo("O-O");
            // The rook must move too — the whole point of castling being one move.
            assertThat(result.positionAfter().fen()).startsWith("r3k2r/8/8/8/8/8/8/R4RK1");
        }

        @Test
        @DisplayName("captures en passant")
        void capturesEnPassant() {
            // Black has just played c7c5; the FEN's en passant target is c6.
            Position position = new Position(
                    "rnbqkbnr/pp1ppppp/8/2pP4/8/8/PPP1PPPP/RNBQKBNR w KQkq c6 0 3");

            MoveResult result = rules.apply(position, MoveIntent.of("d5", "c6"));

            assertThat(result.san()).isEqualTo("dxc6");
            // The captured pawn was on c5, not on the destination square.
            assertThat(result.positionAfter().fen()).startsWith("rnbqkbnr/pp1ppppp/2P5/8/");
        }

        @Test
        @DisplayName("promotes to the requested piece, not always a queen")
        void promotesToRequestedPiece() {
            Position position = new Position("8/P6k/8/8/8/8/8/K7 w - - 0 1");

            MoveResult queened = rules.apply(position,
                    new MoveIntent("a7", "a8", Promotion.QUEEN));
            MoveResult knighted = rules.apply(position,
                    new MoveIntent("a7", "a8", Promotion.KNIGHT));

            assertThat(queened.uci()).isEqualTo("a7a8q");
            assertThat(queened.san()).isEqualTo("a8=Q");
            assertThat(knighted.uci()).isEqualTo("a7a8n");
            assertThat(knighted.san()).isEqualTo("a8=N");
            // Underpromotion is a real tactic. Defaulting to queen would be silently
            // wrong rather than loudly wrong, which is worse.
            assertThat(knighted.positionAfter().fen()).isNotEqualTo(queened.positionAfter().fen());
        }
    }

    @Nested
    @DisplayName("game endings")
    class Endings {

        @Test
        @DisplayName("detects checkmate")
        void detectsCheckmate() {
            // Scholar's mate: 1.e4 e5 2.Bc4 Nc6 3.Qh5 Nf6?? 4.Qxf7#
            Position position = rules.startingPosition();
            for (String uci : new String[]{"e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6"}) {
                position = rules.apply(position, uci(uci)).positionAfter();
            }

            MoveResult mate = rules.apply(position, MoveIntent.of("h5", "f7"));

            assertThat(mate.outcome()).isEqualTo(GameOutcome.CHECKMATE);
            assertThat(mate.outcome().isTerminal()).isTrue();
            assertThat(mate.outcome().isDraw()).isFalse();
            assertThat(mate.san()).endsWith("#");
        }

        @Test
        @DisplayName("detects stalemate")
        void detectsStalemate() {
            // Kh8 has no legal move after Qg6 and is not in check.
            Position position = new Position("7k/5K2/8/8/8/8/8/6Q1 w - - 0 1");

            MoveResult result = rules.apply(position, MoveIntent.of("g1", "g6"));

            assertThat(result.outcome()).isEqualTo(GameOutcome.STALEMATE);
            assertThat(result.outcome().isDraw()).isTrue();
        }

        @Test
        @DisplayName("detects insufficient material after the last piece is captured")
        void detectsInsufficientMaterial() {
            // King takes the last knight, leaving king against king.
            Position position = new Position("8/8/8/4k3/8/8/3nK3/8 w - - 0 1");

            MoveResult result = rules.apply(position, MoveIntent.of("e2", "d2"));

            assertThat(result.outcome()).isEqualTo(GameOutcome.DRAW_INSUFFICIENT_MATERIAL);
        }

        @Test
        @DisplayName("detects the fifty-move rule")
        void detectsFiftyMoveRule() {
            // Halfmove clock at 99; a quiet rook move takes it to 100.
            Position position = new Position("4k3/8/8/8/8/8/8/R3K3 w - - 99 60");

            MoveResult result = rules.apply(position, MoveIntent.of("a1", "a2"));

            assertThat(result.outcome()).isEqualTo(GameOutcome.DRAW_FIFTY_MOVE);
        }
    }

    /**
     * Repetition is the one ending that needs history, so these tests hand the rules the
     * positions a game passed through — exactly what the game module does from its log.
     */
    @Nested
    @DisplayName("threefold repetition")
    class Repetition {

        @Test
        @DisplayName("the third occurrence of a position is a repetition; the second is not")
        void knightShuffle() {
            List<Position> positions = playFrom(rules.startingPosition(),
                    "g1f3", "g8f6", "f3g1", "f6g8",    // start position, second time
                    "g1f3", "g8f6", "f3g1", "f6g8");   // third time

            assertThat(rules.isThreefoldRepetition(positions.get(4), positions.subList(0, 4)))
                    .as("second occurrence").isFalse();
            assertThat(rules.isThreefoldRepetition(positions.get(8), positions.subList(0, 8)))
                    .as("third occurrence").isTrue();
        }

        /**
         * FIDE 9.2.3: a position is not the same if castling rights differ. Here the kings
         * step out and back, so the placement matches the start but nobody can castle.
         */
        @Test
        @DisplayName("positions with different castling rights are different positions")
        void castlingRightsDistinguish() {
            List<Position> positions = playFrom(new Position("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"),
                    "e1f1", "e8f8", "f1e1", "f8e8",    // same placement, rights gone
                    "e1f1", "e8f8", "f1e1", "f8e8",
                    "e1f1", "e8f8", "f1e1", "f8e8");

            assertThat(rules.isThreefoldRepetition(positions.get(8), positions.subList(0, 8)))
                    .as("start had castling rights, so this is only the second rightless one")
                    .isFalse();
            assertThat(rules.isThreefoldRepetition(positions.get(12), positions.subList(0, 12)))
                    .isTrue();
        }

        /**
         * The FEN after 1.e4 carries "e3" although no black pawn can capture — chesslib
         * writes it after every double push. By FIDE the position is the same as the one
         * reached later without it, and treating them as different misses the draw.
         */
        @Test
        @DisplayName("an en-passant square nobody can capture on does not make a position different")
        void uncapturableEnPassantIgnored() {
            List<Position> positions = playFrom(rules.startingPosition(),
                    "e2e4",                            // black to move, FEN says e3
                    "g8f6", "g1f3", "f6g8", "f3g1",    // same, FEN says -
                    "g8f6", "g1f3", "f6g8", "f3g1");

            assertThat(positions.get(1).fen()).contains(" e3 ");
            assertThat(rules.isThreefoldRepetition(positions.get(9), positions.subList(0, 9)))
                    .isTrue();
        }

        /** And the converse: when the capture really was available, the positions differ. */
        @Test
        @DisplayName("a capturable en-passant square does make a position different")
        void capturableEnPassantDistinguishes() {
            List<Position> positions = playFrom(new Position("4k3/8/8/8/3p4/8/4P3/4K3 w - - 0 1"),
                    "e2e4",                            // ...dxe3 e.p. is legal here
                    "e8e7", "e1f1", "e7e8", "f1e1",    // same placement, capture gone
                    "e8e7", "e1f1", "e7e8", "f1e1");

            assertThat(rules.legalMoves(positions.get(1))).contains("d4e3");
            assertThat(rules.isThreefoldRepetition(positions.get(9), positions.subList(0, 9)))
                    .isFalse();
        }

        @Test
        @DisplayName("the same placement with the other side to move is a different position")
        void sideToMoveDistinguishes() {
            Position whiteToMove = new Position("4k3/8/8/8/8/8/8/4K2R w - - 0 1");
            Position blackToMove = new Position("4k3/8/8/8/8/8/8/4K2R b - - 1 1");

            assertThat(rules.isThreefoldRepetition(whiteToMove, List.of(blackToMove, blackToMove)))
                    .isFalse();
        }

        @Test
        @DisplayName("move counters are ignored")
        void countersIgnored() {
            Position now = new Position("4k3/8/8/8/8/8/8/4K2R w - - 12 40");

            assertThat(rules.isThreefoldRepetition(now, List.of(
                    new Position("4k3/8/8/8/8/8/8/4K2R w - - 4 36"),
                    new Position("4k3/8/8/8/8/8/8/4K2R w - - 8 38"))))
                    .isTrue();
        }

        @Test
        @DisplayName("apply() never reports repetition on its own — it has no history")
        void applyIsHistoryless() {
            List<Position> positions = playFrom(rules.startingPosition(),
                    "g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1");

            MoveResult third = rules.apply(positions.getLast(), uci("f6g8"));

            assertThat(third.outcome()).isEqualTo(GameOutcome.IN_PROGRESS);
        }

        @Test
        @DisplayName("halfmove clock counts plies since the last capture or pawn move")
        void halfmoveClock() {
            List<Position> positions = playFrom(rules.startingPosition(), "g1f3", "g8f6", "e2e4");

            assertThat(positions.get(2).halfmoveClock()).isEqualTo(2);
            assertThat(positions.get(3).halfmoveClock()).isZero();
        }

        private List<Position> playFrom(Position start, String... moves) {
            List<Position> positions = new ArrayList<>();
            positions.add(start);
            Position current = start;
            for (String move : moves) {
                current = rules.apply(current, uci(move)).positionAfter();
                positions.add(current);
            }
            return positions;
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        /**
         * The property ADR-002's wrapper exists to guarantee.
         *
         * <p>chesslib's {@code Board} is mutable and not thread-safe. If the adapter ever
         * cached or shared one, two threads evaluating different positions would corrupt
         * each other — and in this system that is the normal case, since every game is
         * being played concurrently with every other.
         *
         * <p>This fails loudly if someone later "optimises" the adapter by hoisting the
         * board into a field.
         */
        @Test
        @DisplayName("is safe under concurrent use across different positions")
        void isThreadSafe() throws Exception {
            int threads = 16;
            int iterations = 200;
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);
            var failures = new AtomicInteger();

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int t = 0; t < threads; t++) {
                    boolean openWithPawn = t % 2 == 0;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < iterations; i++) {
                                MoveResult result = rules.apply(
                                        rules.startingPosition(),
                                        openWithPawn ? MoveIntent.of("e2", "e4")
                                                     : MoveIntent.of("g1", "f3"));
                                String expected = openWithPawn ? "e4" : "Nf3";
                                if (!expected.equals(result.san())) {
                                    failures.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(failures)
                    .as("no thread may observe another thread's board state")
                    .hasValue(0);
        }
    }

    private static MoveIntent uci(String move) {
        return MoveIntent.of(move.substring(0, 2), move.substring(2, 4));
    }
}

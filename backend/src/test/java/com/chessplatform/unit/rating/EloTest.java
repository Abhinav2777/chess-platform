package com.chessplatform.unit.rating;

import com.chessplatform.rating.internal.Elo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Elo")
class EloTest {

    @Test
    @DisplayName("equal players: a win is worth K/2, a draw nothing")
    void equalPlayers() {
        assertThat(Elo.change(1500, 1500, 1.0)).isEqualTo(new Elo.Change(16, -16));
        assertThat(Elo.change(1500, 1500, 0.0)).isEqualTo(new Elo.Change(-16, 16));
        assertThat(Elo.change(1500, 1500, 0.5)).isEqualTo(new Elo.Change(0, 0));
    }

    @Test
    @DisplayName("an upset moves more rating than the expected result")
    void upsets() {
        Elo.Change expected = Elo.change(1900, 1500, 1.0);   // favourite wins
        Elo.Change upset = Elo.change(1500, 1900, 1.0);      // underdog wins

        assertThat(expected.white()).isBetween(1, 5);
        assertThat(upset.white()).isBetween(27, 31);
    }

    @Test
    @DisplayName("a draw against a stronger player gains rating")
    void drawUp() {
        assertThat(Elo.change(1400, 1800, 0.5).white()).isPositive();
    }

    /** Zero-sum by construction: no rounding can create or destroy a rating point. */
    @ParameterizedTest(name = "{0} vs {1}, score {2}")
    @CsvSource({"1200,1200,1", "1213,1187,0.5", "2400,800,0", "800,2400,1", "1500,1501,0.5"})
    @DisplayName("the two changes always cancel")
    void zeroSum(int white, int black, double score) {
        Elo.Change change = Elo.change(white, black, score);
        assertThat(change.white() + change.black()).isZero();
    }
}

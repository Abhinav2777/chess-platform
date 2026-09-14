package com.chessplatform.chess;

import java.util.Locale;
import java.util.Objects;

/**
 * What a player is asking to do: move a piece from one square to another.
 *
 * <p>Structured squares, not a SAN or UCI string. The client is authoritative only about
 * its intent (ARCHITECTURE.md §10); parsing notation server-side would mean accepting a
 * string whose meaning depends on the position, which is a validation surface we do not
 * need. From, to, and an explicit promotion are unambiguous in any position.
 *
 * @param promotion null unless the move is a pawn promotion
 */
public record MoveIntent(String from, String to, Promotion promotion) {

    public MoveIntent {
        from = normaliseSquare(from, "from");
        to = normaliseSquare(to, "to");
        if (from.equals(to)) {
            throw new IllegalArgumentException("from and to must differ: " + from);
        }
    }

    public static MoveIntent of(String from, String to) {
        return new MoveIntent(from, to, null);
    }

    /** e2e4, or e7e8q for a promotion. The form chesslib and the UCI protocol expect. */
    public String toUci() {
        return promotion == null ? from + to : from + to + promotion.uciSuffix();
    }

    private static String normaliseSquare(String square, String field) {
        Objects.requireNonNull(square, field);
        String normalised = square.strip().toLowerCase(Locale.ROOT);
        if (!normalised.matches("^[a-h][1-8]$")) {
            throw new IllegalArgumentException(
                    field + " must be an algebraic square such as e4; got: " + square);
        }
        return normalised;
    }
}

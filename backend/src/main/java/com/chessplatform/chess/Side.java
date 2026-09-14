package com.chessplatform.chess;

/** Whose turn it is. Our own type — chesslib's {@code Side} never leaves the adapter. */
public enum Side {
    WHITE("w"),
    BLACK("b");

    private final String fenCode;

    Side(String fenCode) {
        this.fenCode = fenCode;
    }

    /** The single character stored in {@code games.side_to_move}, matching FEN notation. */
    public String fenCode() {
        return fenCode;
    }

    public Side opponent() {
        return this == WHITE ? BLACK : WHITE;
    }

    public static Side fromFenCode(String code) {
        return switch (code) {
            case "w" -> WHITE;
            case "b" -> BLACK;
            default -> throw new IllegalArgumentException("not a side: " + code);
        };
    }
}

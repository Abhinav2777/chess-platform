package com.chessplatform.common.error;

/**
 * Stable, machine-readable error identifiers.
 *
 * <p>These are part of the API contract. A client may branch on {@code USERNAME_TAKEN}
 * to highlight a form field; it must never have to branch on an English message string.
 * That means <strong>renaming a constant here is a breaking API change</strong>, while
 * rewording the human-facing message is not.
 *
 * <p>Deliberately not an exhaustive catalogue up front. Codes are added when a caller
 * actually needs to distinguish a case — an enum full of speculative values is a
 * maintenance cost with no consumer.
 */
public enum ErrorCode {

    // --- identity -----------------------------------------------------------
    USERNAME_TAKEN,
    EMAIL_TAKEN,
    INVALID_CREDENTIALS,
    USER_NOT_FOUND,

    // --- chess / game --------------------------------------------------------
    ILLEGAL_MOVE,
    NOT_YOUR_TURN,
    GAME_NOT_ACTIVE,
    OUT_OF_TIME,
    GAME_NOT_FOUND,
    NOT_A_PLAYER,

    // --- generic ------------------------------------------------------------
    VALIDATION_FAILED,
    CONFLICT
}

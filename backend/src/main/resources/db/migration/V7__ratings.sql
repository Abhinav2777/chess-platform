-- V7: rating consumer (Phase 5, Milestone 5.2).

-- Idempotency for at-least-once delivery (ADR-008). A consumer inserts the event id in the
-- SAME transaction as its side effect; a redelivered event finds its row and does nothing.
-- Keyed by consumer as well, so a second consumer of the same events (notifications, one
-- day) keeps its own record instead of mistaking the rating consumer's for its own.
CREATE TABLE processed_events (
    consumer      VARCHAR(64)  NOT NULL,
    event_id      UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- Every rating change, with its cause. The primary key is the structural backstop behind
-- processed_events: even if a bug minted a second event id for the same game, the second
-- rating of it could not be stored — the insert fails, the transaction rolls back, and the
-- message ends in the dead-letter queue where a human sees it (ADR-005's layering, again).
CREATE TABLE rating_history (
    game_id        UUID         NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    user_id        UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_id       UUID         NOT NULL,
    rating_before  INTEGER      NOT NULL,
    rating_after   INTEGER      NOT NULL,
    delta          INTEGER      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, user_id),
    CONSTRAINT ck_rating_history_delta CHECK (rating_after - rating_before = delta)
);

-- "My rating over time": a player's history, newest first.
CREATE INDEX idx_rating_history_user ON rating_history (user_id, created_at DESC);

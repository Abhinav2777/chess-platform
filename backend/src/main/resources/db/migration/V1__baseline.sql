-- V1: baseline schema — users, games, moves.
--
-- Clock columns are deliberately NOT here. They arrive in V2 during Phase 3, alongside
-- the code that uses them. Adding columns ahead of the feature means shipping a schema
-- nobody can explain, and it breaks the discipline that every migration corresponds to
-- a working change.
--
-- Migrations are immutable once committed. Never edit this file after it has run
-- anywhere. Fix forward with a new version.

-- pgcrypto gives us gen_random_uuid(). We generate UUIDs in the application (UUIDv7,
-- time-ordered) but a database-side default is a useful safety net for manual inserts.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,   -- bcrypt: 60 chars; 100 leaves room for a
                                           -- future algorithm change without a migration
    rating        INTEGER      NOT NULL DEFAULT 1200,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT ck_users_rating   CHECK (rating BETWEEN 0 AND 4000)
);

-- Leaderboard reads. Cheap to add now, awkward to add later under load.
CREATE INDEX idx_users_rating ON users (rating DESC);

-- ---------------------------------------------------------------------------
-- games
-- ---------------------------------------------------------------------------
CREATE TABLE games (
    id              UUID         PRIMARY KEY,
    white_player_id UUID         NOT NULL REFERENCES users (id),
    black_player_id UUID         NOT NULL REFERENCES users (id),

    status          VARCHAR(16)  NOT NULL,   -- ACTIVE | FINISHED | ABORTED
    result          VARCHAR(16),             -- WHITE_WIN | BLACK_WIN | DRAW | NULL
    termination     VARCHAR(24),             -- CHECKMATE | STALEMATE | RESIGNATION |
                                             -- DRAW_FIFTY_MOVE | DRAW_REPETITION |
                                             -- DRAW_INSUFFICIENT | DRAW_AGREEMENT |
                                             -- ABANDONED  (TIMEOUT added in V2)

    -- Denormalised current position. Rebuildable by replaying `moves`, which is what
    -- makes this column safe to hold and the move log the real record (ADR-004).
    fen             VARCHAR(100) NOT NULL,
    ply             INTEGER      NOT NULL DEFAULT 0,
    side_to_move    CHAR(1)      NOT NULL,   -- 'w' | 'b'

    -- Optimistic locking. Managed by JPA @Version. See ADR-005.
    version         BIGINT       NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,

    CONSTRAINT ck_games_distinct_players CHECK (white_player_id <> black_player_id),
    CONSTRAINT ck_games_status           CHECK (status IN ('ACTIVE', 'FINISHED', 'ABORTED')),
    CONSTRAINT ck_games_side             CHECK (side_to_move IN ('w', 'b')),
    CONSTRAINT ck_games_ply              CHECK (ply >= 0),

    -- A finished game must have a result; an active one must not. Enforcing this in the
    -- database means no code path can produce a half-finished game, including a bug in
    -- a future migration or a manual fix at 3am.
    CONSTRAINT ck_games_result_consistency CHECK (
        (status = 'FINISHED' AND result IS NOT NULL AND finished_at IS NOT NULL)
        OR (status <> 'FINISHED' AND result IS NULL)
    )
);

-- Game history per player, newest first. Two separate indexes rather than one on a
-- computed "either player" expression: Postgres can use either directly, and the query
-- is a UNION of two index scans.
CREATE INDEX idx_games_white ON games (white_player_id, created_at DESC);
CREATE INDEX idx_games_black ON games (black_player_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- moves — append-only. Never updated, never deleted except by game cascade.
-- ---------------------------------------------------------------------------
CREATE TABLE moves (
    game_id        UUID         NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    ply            INTEGER      NOT NULL,

    uci            VARCHAR(6)   NOT NULL,   -- e2e4, e7e8q
    san            VARCHAR(10)  NOT NULL,   -- Nf3, exd8=Q+
    fen_after      VARCHAR(100) NOT NULL,

    -- Client-generated idempotency key. The unique index below is the mechanism that
    -- makes a retried move safe (ADR-005). Not a nice-to-have: without it, a lost ACK
    -- forces the client to choose between losing a move and playing it twice.
    client_move_id UUID         NOT NULL,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- The natural key. Makes "replay a game in order" a single index range scan, and
    -- makes two moves at the same ply structurally impossible.
    PRIMARY KEY (game_id, ply),

    CONSTRAINT ck_moves_ply CHECK (ply > 0)
);

CREATE UNIQUE INDEX uq_moves_client_id ON moves (game_id, client_move_id);

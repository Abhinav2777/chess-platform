-- V4: the server-authoritative chess clock (ADR-006).
--
-- Five columns and one index are the entire clock. There is no ticking process, no timer
-- per game, and no in-memory state anywhere: remaining time is DERIVED from these columns
-- and the current time, every time it is read.
--
-- That is what makes a player able to disconnect and reconnect to a DIFFERENT instance
-- mid-game and see a correct clock. Nothing was ever held in the instance they left.

ALTER TABLE games
    ADD COLUMN initial_ms    BIGINT,
    ADD COLUMN increment_ms  BIGINT,
    ADD COLUMN white_ms_left BIGINT,
    ADD COLUMN black_ms_left BIGINT,

    -- When the clock last changed hands. Elapsed time for the side to move is
    -- (now - last_move_at), so this plus *_ms_left is the whole state.
    ADD COLUMN last_move_at  TIMESTAMPTZ,

    -- last_move_at + (the mover's remaining time). Stored rather than computed so the
    -- timeout sweeper can find expired games with an index scan bounded by the number of
    -- EXPIRED games, rather than scanning every game ever played. A derived expression
    -- cannot be indexed usefully; this is the difference between O(expired) and O(all).
    ADD COLUMN turn_deadline TIMESTAMPTZ;

-- Backfill: games created before the clock existed get a default 5+3 and a deadline
-- measured from when they started.
UPDATE games
   SET initial_ms    = 300000,
       increment_ms  = 3000,
       white_ms_left = 300000,
       black_ms_left = 300000,
       last_move_at  = created_at,
       turn_deadline = created_at + INTERVAL '300 seconds'
 WHERE initial_ms IS NULL;

ALTER TABLE games
    ALTER COLUMN initial_ms    SET NOT NULL,
    ALTER COLUMN increment_ms  SET NOT NULL,
    ALTER COLUMN white_ms_left SET NOT NULL,
    ALTER COLUMN black_ms_left SET NOT NULL,
    ALTER COLUMN last_move_at  SET NOT NULL,
    ALTER COLUMN turn_deadline SET NOT NULL;

-- No column defaults, deliberately. A default would let a bug that forgets to set the
-- clock produce a silently-playable 5+3 game instead of failing loudly.

ALTER TABLE games
    ADD CONSTRAINT ck_games_clock_nonnegative
        CHECK (white_ms_left >= 0 AND black_ms_left >= 0
               AND initial_ms > 0 AND increment_ms >= 0);

-- Partial index: only ACTIVE games can time out, and they are a small fraction of the
-- table on any platform that has been running a while.
CREATE INDEX idx_games_active_deadline ON games (turn_deadline)
    WHERE status = 'ACTIVE';

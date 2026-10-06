-- DRAW_INSUFFICIENT_MATERIAL is 26 characters; V1 made the column 24. Every game that ended by
-- insufficient material failed to save — the move's transaction rolled back, and an over-broad
-- catch reported it to the player as a duplicate move (found in Phase 10.3, reading the schema
-- against the enum). Widening a VARCHAR is a catalogue-only change in PostgreSQL: no table
-- rewrite, no long lock.
ALTER TABLE games ALTER COLUMN termination TYPE VARCHAR(32);

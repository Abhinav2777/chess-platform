-- V5: games nobody starts are aborted, not played (Milestone 3.2).
--
-- No schema change. `turn_deadline` now means "the next instant this game needs the
-- server's attention if nobody moves": the mover's flag-fall, or — until both players
-- have made a move — the end of the 30-second first-move window, whichever is sooner.
-- Folding both into one column keeps ONE partial index, ONE `SKIP LOCKED` query and ONE
-- sweeper for timeouts and aborts alike; `Game.expiryAt` decides which applies.
--
-- This backfill brings games already in progress in line with the new rule, so an old
-- unstarted game is aborted within a sweep of the migration rather than sitting until its
-- five-minute clock runs out and THEN being aborted. Only ACTIVE games at ply 0 or 1 can
-- be affected; LEAST never moves a deadline later.
--
-- The 30 seconds mirrors Game.FIRST_MOVE_WINDOW. It is a literal on purpose: a migration
-- records what was true when it ran and must never change meaning because Java code did.

UPDATE games
   SET turn_deadline = LEAST(turn_deadline, last_move_at + INTERVAL '30 seconds')
 WHERE status = 'ACTIVE'
   AND ply < 2;

-- Aborted games carry a termination (ABANDONED) but never a result. The existing
-- ck_games_result_consistency already forbids a result on any non-FINISHED game, so the
-- "an abort is never rated" rule is enforced by the database without a new constraint.

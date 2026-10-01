-- Phase 9 (ADR-026): the W3C traceparent of the transaction that wrote the event.
--
-- The outbox is an asynchronous boundary: the relay sends the row later, from a scheduled job,
-- in a different trace. Stored here and sent as the SQS message's `traceparent` attribute, it
-- lets the consumer's work join the trace of the move that ended the game — one timeline from
-- the player's click to the rating update. "00-<32 hex>-<16 hex>-<2 hex>" = 55 characters.
-- Nullable: rows written before this migration, or with tracing off, simply start new traces.
ALTER TABLE outbox ADD COLUMN trace_parent VARCHAR(55);

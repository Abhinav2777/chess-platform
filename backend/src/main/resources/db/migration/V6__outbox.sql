-- V6: transactional outbox (Phase 5, ADR-008).
--
-- A domain event is written here IN THE SAME TRANSACTION as the change it describes, and a
-- relay publishes it to SQS afterwards. That closes the window a direct SQS call leaves
-- open: game committed, process dies before the send, game never rated.
--
-- Delivery from here is at-least-once: a relay that sends and then dies before marking the
-- row sends it again. Consumers deduplicate on `id` (processed_events, V7).

CREATE TABLE outbox (
    -- The event id. UUIDv7, so the primary key is also creation order: the relay reads
    -- oldest first straight off the index.
    id            UUID          PRIMARY KEY,
    event_type    VARCHAR(64)   NOT NULL,
    -- What the event is about (a game id). With event_type, the uniqueness key below.
    aggregate_id  UUID          NOT NULL,
    payload       JSONB         NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- NULL until sent. Rows are kept after publishing, for a few days of "what did we send"
    -- when debugging a consumer; a purge job is recorded as a follow-up, not built.
    published_at  TIMESTAMPTZ,
    attempts      INTEGER       NOT NULL DEFAULT 0,
    last_error    VARCHAR(500)
);

-- The relay's query: unpublished rows, oldest first. Partial, so its size tracks the
-- backlog (normally near zero) rather than every event ever sent.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- A game finishes once, so at most one GAME_FINISHED per game — enforced by the database,
-- whatever a future code path does. Consumers deduplicate on event id; this stops a second
-- event id ever being minted for the same fact.
CREATE UNIQUE INDEX uq_outbox_event_per_aggregate ON outbox (event_type, aggregate_id);

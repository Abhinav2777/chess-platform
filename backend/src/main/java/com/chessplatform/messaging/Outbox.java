package com.chessplatform.messaging;

import com.chessplatform.common.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Records a domain event for asynchronous delivery (ADR-008).
 *
 * <h2>Only inside the caller's transaction</h2>
 *
 * <p>{@code MANDATORY}: calling this without a transaction is an error, not a convenience.
 * The whole point is that the event row commits or rolls back <em>with</em> the change it
 * describes — a game that finished has its event, and a move that rolled back has none.
 * Appending in a transaction of its own would reopen exactly the gap the outbox exists to
 * close.
 *
 * <p>Plain SQL rather than an entity: the relay needs {@code FOR UPDATE SKIP LOCKED} and a
 * {@code jsonb} cast, and a JDBC write joins the surrounding JPA transaction on the same
 * connection.
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public Outbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param eventType   the message's type, e.g. {@code GAME_FINISHED}
     * @param aggregateId what the event is about. {@code (eventType, aggregateId)} is unique
     *                    (V6), so a fact is recorded at most once
     * @param payload     serialised to JSON; the consumer's contract
     * @return the event id consumers deduplicate on
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String eventType, UUID aggregateId, Object payload) {
        UUID eventId = Uuid7.generate();
        jdbc.update("INSERT INTO outbox (id, event_type, aggregate_id, payload) VALUES (?, ?, ?, ?::jsonb)",
                eventId, eventType, aggregateId, json.writeValueAsString(payload));
        return eventId;
    }
}

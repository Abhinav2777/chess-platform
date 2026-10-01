package com.chessplatform.messaging;

import com.chessplatform.common.id.Uuid7;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
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
    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public Outbox(JdbcTemplate jdbc, JsonMapper json,
                  ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.jdbc = jdbc;
        this.json = json;
        this.tracer = tracer;
        this.propagator = propagator;
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
        jdbc.update("INSERT INTO outbox (id, event_type, aggregate_id, payload, trace_parent) VALUES (?, ?, ?, ?::jsonb, ?)",
                eventId, eventType, aggregateId, json.writeValueAsString(payload), currentTraceParent());
        return eventId;
    }

    /**
     * The current span as a W3C {@code traceparent}, stored with the event (V8, ADR-026). The
     * relay sends the row later from its own trace; carrying this lets the consumer's work join
     * the trace of the request that produced the event, across the outbox's async boundary.
     * Null when there is no current span or no tracer — the event then starts a new trace.
     */
    private String currentTraceParent() {
        Tracer activeTracer = tracer.getIfAvailable();
        Propagator activePropagator = propagator.getIfAvailable();
        if (activeTracer == null || activePropagator == null) {
            return null;
        }
        TraceContext context = activeTracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        activePropagator.inject(context, carrier, Map::put);
        return carrier.get("traceparent");
    }
}

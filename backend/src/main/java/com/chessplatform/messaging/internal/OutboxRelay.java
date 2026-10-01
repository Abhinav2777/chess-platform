package com.chessplatform.messaging.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.awspring.cloud.sqs.operations.SendResult;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Moves outbox rows to SQS. At-least-once, by construction.
 *
 * <h2>One transaction: claim, send, mark</h2>
 *
 * <p>Rows are claimed with {@code FOR UPDATE SKIP LOCKED} — the timeout sweeper's pattern —
 * so relays on several instances take disjoint batches with no coordination. The send
 * happens while the claim is held; the rows are marked published in the same transaction.
 *
 * <p>The failure this admits, deliberately: SQS accepts a batch and the instance dies (or the
 * commit fails) before the mark. The rows are still unpublished and go again — a duplicate
 * send. Consumers deduplicate on the event id (ADR-008). The failure it rules out: an event
 * that is never sent at all. Duplicates are recoverable; losses are not.
 *
 * <p>Holding a row lock across a network call is normally a smell. Here the lock is on
 * rows nobody else wants — only other relays, which skip them — and the SQS client's
 * timeouts bound the transaction to seconds.
 */
@Component
public class OutboxRelay {

    static final String EVENT_ID_HEADER = "eventId";
    /** W3C trace context, from the outbox row: the listener's observation continues it (ADR-026). */
    static final String TRACEPARENT_HEADER = "traceparent";

    /** SendMessageBatch's per-call maximum. */
    private static final int SQS_BATCH = 10;
    /** After this many failed sends a row stops being retried and shows up as stuck. */
    static final int MAX_ATTEMPTS = 20;

    private final JdbcTemplate jdbc;
    private final SqsTemplate sqs;
    private final SqsQueues queues;
    private final JsonMapper json;
    private final MessagingProperties properties;

    private final Counter published;
    private final Counter failed;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong stuck = new AtomicLong();

    public OutboxRelay(JdbcTemplate jdbc, SqsTemplate sqs, SqsQueues queues, JsonMapper json,
                       MessagingProperties properties, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.sqs = sqs;
        this.queues = queues;
        this.json = json;
        this.properties = properties;
        this.published = Counter.builder("chess.outbox.published")
                .description("Outbox events sent to SQS").register(metrics);
        this.failed = Counter.builder("chess.outbox.send_failures")
                .description("Outbox events SQS refused or could not be reached for").register(metrics);
        Gauge.builder("chess.outbox.backlog", backlog, AtomicLong::get)
                .description("Unpublished outbox events").register(metrics);
        // The one to alarm on: a backlog of one that is ten minutes old is an outage; fifty
        // that are a second old is a busy second.
        Gauge.builder("chess.outbox.oldest_age_seconds", oldestAgeSeconds, AtomicLong::get)
                .description("Age of the oldest unpublished outbox event").register(metrics);
        Gauge.builder("chess.outbox.stuck", stuck, AtomicLong::get)
                .description("Events that exhausted their send attempts and need a human").register(metrics);
    }

    record Row(UUID id, String eventType, UUID aggregateId, String payload, Instant createdAt,
               String traceParent) {
    }

    /** Claims and sends one batch. Returns how many rows it claimed. */
    @Transactional
    public int relayOnce() {
        List<Row> rows = jdbc.query("""
                        SELECT id, event_type, aggregate_id, payload::text AS payload, created_at, trace_parent
                          FROM outbox
                         WHERE published_at IS NULL AND attempts < ?
                         ORDER BY id
                         LIMIT ?
                           FOR UPDATE SKIP LOCKED
                        """,
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getObject("aggregate_id", UUID.class), rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("trace_parent")),
                MAX_ATTEMPTS, properties.relayBatchSize());
        if (rows.isEmpty()) {
            return 0;
        }
        // Throws if SQS is unreachable: the transaction rolls back, nothing is marked, the
        // rows go again next tick.
        String queueUrl = queues.queueUrl();

        for (int from = 0; from < rows.size(); from += SQS_BATCH) {
            send(queueUrl, rows.subList(from, Math.min(from + SQS_BATCH, rows.size())));
        }
        return rows.size();
    }

    private void send(String queueUrl, List<Row> chunk) {
        Map<String, Row> byEventId = new HashMap<>();
        List<Message<String>> messages = new ArrayList<>();
        for (Row row : chunk) {
            byEventId.put(row.id().toString(), row);
            // The header maps each per-message result back to its row, and travels as an SQS
            // message attribute — so the event id is visible without parsing the body.
            MessageBuilder<String> message = MessageBuilder.withPayload(envelope(row))
                    .setHeader(EVENT_ID_HEADER, row.id().toString());
            // Sent as a message attribute. sendMany is not observed by Spring Cloud AWS (only the
            // single-message path is), so nothing overwrites it with the relay's own trace.
            if (row.traceParent() != null) {
                message.setHeader(TRACEPARENT_HEADER, row.traceParent());
            }
            messages.add(message.build());
        }

        SendResult.Batch<String> result;
        try {
            result = sqs.sendMany(queueUrl, messages);
        } catch (RuntimeException unreachable) {
            // The whole batch failed (SQS unreachable, timeout). Nothing is marked published;
            // every row is retried next tick.
            for (Row row : chunk) {
                markFailed(row, unreachable.toString());
            }
            return;
        }
        // Per-message outcome: SQS can accept part of a batch. One bad message must not hold
        // back, or be mistaken for, its neighbours.
        for (SendResult<String> ok : result.successful()) {
            markPublished(byEventId.get(eventIdOf(ok.message())));
        }
        for (SendResult.Failed<String> failure : result.failed()) {
            markFailed(byEventId.get(eventIdOf(failure.message())), failure.errorMessage());
        }
    }

    private static String eventIdOf(Message<?> message) {
        return String.valueOf(message.getHeaders().get(EVENT_ID_HEADER));
    }

    /**
     * Self-describing: whoever reads this — the consumer, or a human looking at the
     * dead-letter queue at 3 a.m. — has the event id, type and subject without a lookup.
     */
    private String envelope(Row row) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventId", row.id().toString());
        envelope.put("eventType", row.eventType());
        envelope.put("aggregateId", row.aggregateId().toString());
        envelope.put("occurredAt", row.createdAt().toString());
        envelope.set("payload", json.readTree(row.payload()));
        return json.writeValueAsString(envelope);
    }

    private void markPublished(Row row) {
        jdbc.update("UPDATE outbox SET published_at = now(), attempts = attempts + 1 WHERE id = ?", row.id());
        published.increment();
    }

    private void markFailed(Row row, String error) {
        String truncated = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?", truncated, row.id());
        failed.increment();
    }

    /** Refreshes the backlog gauges. Cheap: the partial index covers exactly these rows. */
    public void refreshMetrics() {
        jdbc.query("""
                SELECT count(*) FILTER (WHERE attempts < ?) AS pending,
                       count(*) FILTER (WHERE attempts >= ?) AS stuck,
                       min(created_at) AS oldest
                  FROM outbox WHERE published_at IS NULL
                """, rs -> {
            backlog.set(rs.getLong("pending"));
            stuck.set(rs.getLong("stuck"));
            Timestamp oldest = rs.getTimestamp("oldest");
            oldestAgeSeconds.set(oldest == null ? 0
                    : Math.max(0, (System.currentTimeMillis() - oldest.getTime()) / 1000));
        }, MAX_ATTEMPTS, MAX_ATTEMPTS);
    }
}

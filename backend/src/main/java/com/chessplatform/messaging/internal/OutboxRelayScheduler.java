package com.chessplatform.messaging.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay once a second on instances in the worker role (ADR-001).
 *
 * <p>Separate from {@link OutboxRelay} so its {@code @Transactional} is reached through the
 * proxy — the self-invocation trap that once kept the timeout sweeper from finalising
 * anything (3.1) — and so tests can switch the schedule off and drive batches themselves.
 *
 * <p>A full batch means there may be more: drain up to a bound, then yield to the next tick
 * rather than hold the scheduler thread for an unbounded backlog.
 */
@Component
@ConditionalOnProperty(name = "chess.messaging.relay-enabled", havingValue = "true")
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);
    private static final int MAX_BATCHES_PER_TICK = 20;

    private final OutboxRelay relay;
    private final MessagingProperties properties;

    public OutboxRelayScheduler(OutboxRelay relay, MessagingProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 1_000)
    public void run() {
        try {
            for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
                if (relay.relayOnce() < properties.relayBatchSize()) {
                    break;
                }
            }
        } catch (RuntimeException failure) {
            // Never propagate out of @Scheduled (it would cancel the schedule for the life of
            // the process). SQS down is the expected case: the rows wait in the outbox.
            log.warn("Outbox relay failed; retrying next tick: {}", failure.toString());
        } finally {
            try {
                relay.refreshMetrics();
            } catch (RuntimeException ignored) {
                // Metrics must never be the reason the relay logs an error.
            }
        }
    }
}

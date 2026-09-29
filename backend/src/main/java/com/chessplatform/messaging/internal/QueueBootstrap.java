package com.chessplatform.messaging.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Creates the queues — with the redrive policy — before any SQS listener starts.
 *
 * <p>Only where {@code create-queues} is on (ElasticMQ: local and tests). Listener containers
 * resolve their queue when they start, and with {@code queue-not-found-strategy: fail} a
 * fresh ElasticMQ would stop them. They start in the last lifecycle phase
 * ({@code Integer.MAX_VALUE}); this runs in phase 0, so the queues exist first. In AWS,
 * Terraform owns the queues and this bean does not exist.
 */
@Component
@ConditionalOnProperty(name = "chess.messaging.create-queues", havingValue = "true")
public class QueueBootstrap implements SmartLifecycle {

    private final SqsQueues queues;
    private volatile boolean running;

    public QueueBootstrap(SqsQueues queues) {
        this.queues = queues;
    }

    @Override
    public void start() {
        queues.queueUrl();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}

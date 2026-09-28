package com.chessplatform.messaging.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p>Connection settings — region, endpoint override, credentials — are Spring Cloud AWS's
 * ({@code spring.cloud.aws.*}); these are the application's own.
 *
 * @param relayEnabled     run the outbox relay on this instance (the worker role, ADR-001)
 * @param queue            the events queue name
 * @param deadLetterQueue  where a message goes after {@code maxReceiveCount} failed receives
 * @param maxReceiveCount  receives before dead-lettering (ADR-008: 3)
 * @param createQueues     create both queues with the redrive policy on first use. True for
 *                         ElasticMQ; false in AWS, where Terraform owns them (Phase 7)
 * @param relayBatchSize   outbox rows claimed per relay transaction
 */
@ConfigurationProperties(prefix = "chess.messaging")
public record MessagingProperties(boolean relayEnabled,
                                  String queue,
                                  String deadLetterQueue,
                                  int maxReceiveCount,
                                  boolean createQueues,
                                  int relayBatchSize) {

    public MessagingProperties {
        if (queue == null || queue.isBlank() || deadLetterQueue == null || deadLetterQueue.isBlank()) {
            throw new IllegalArgumentException("chess.messaging queue and dead-letter-queue are required");
        }
        if (maxReceiveCount < 1 || relayBatchSize < 1) {
            throw new IllegalArgumentException("chess.messaging max-receive-count and relay-batch-size must be positive");
        }
    }
}

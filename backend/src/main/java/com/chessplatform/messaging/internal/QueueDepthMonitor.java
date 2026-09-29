package com.chessplatform.messaging.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Queue depths as metrics: {@code chess.sqs.messages{queue}}.
 *
 * <p>The dead-letter queue is the one that matters. Anything in it is a rating that did not
 * happen and will not happen without a human (ADR-008: "a poison message must be visible, not
 * silently dropped"). In AWS a CloudWatch alarm on the DLQ does the same job natively (Phase
 * 7); this gauge makes it visible locally and in Grafana (Phase 9).
 *
 * <p>Sampled every 15 s from {@code ApproximateNumberOfMessages} — approximate by SQS's own
 * definition, which is fine for "is it zero".
 */
@Component
@ConditionalOnProperty(name = "chess.messaging.relay-enabled", havingValue = "true")
public class QueueDepthMonitor {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthMonitor.class);

    private final SqsAsyncClient sqs;
    private final SqsQueues queues;
    private final AtomicLong mainDepth = new AtomicLong();
    private final AtomicLong deadLetterDepth = new AtomicLong();

    public QueueDepthMonitor(SqsAsyncClient sqs, SqsQueues queues, MessagingProperties properties,
                             MeterRegistry metrics) {
        this.sqs = sqs;
        this.queues = queues;
        Gauge.builder("chess.sqs.messages", mainDepth, AtomicLong::get)
                .tag("queue", properties.queue())
                .description("Messages waiting in the queue (approximate)").register(metrics);
        Gauge.builder("chess.sqs.messages", deadLetterDepth, AtomicLong::get)
                .tag("queue", properties.deadLetterQueue())
                .description("Messages in the dead-letter queue — should be zero").register(metrics);
    }

    @Scheduled(fixedDelay = 15_000)
    public void sample() {
        try {
            mainDepth.set(depth(queues.queueUrl()));
            long dead = depth(queues.deadLetterQueueUrl());
            if (dead > 0 && deadLetterDepth.get() == 0) {
                log.error("{} message(s) in the dead-letter queue — ratings that did not happen", dead);
            }
            deadLetterDepth.set(dead);
        } catch (RuntimeException unavailable) {
            // Keep the last values; the relay logs SQS outages already.
            log.debug("Queue depth unavailable: {}", unavailable.toString());
        }
    }

    private long depth(String queueUrl) {
        String value = sqs.getQueueAttributes(r -> r.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .join().attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        return value == null ? 0 : Long.parseLong(value);
    }
}

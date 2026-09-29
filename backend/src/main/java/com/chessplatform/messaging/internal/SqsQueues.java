package com.chessplatform.messaging.internal;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;

/**
 * Queue URLs, resolved on first use and then cached.
 *
 * <p>Lazily, deliberately. Only the relay and the consumer talk to SQS; an API instance that
 * failed to start because SQS was unreachable would turn a rating delay into a chess outage.
 *
 * <p>The only place queues are created: {@code SqsTemplate} is configured to fail on a
 * missing queue rather than create one without a redrive policy (see {@code SqsSetup}).
 *
 * <p>With {@code create-queues} (ElasticMQ), both queues are created on first use: the
 * dead-letter queue first, then the main queue with a redrive policy pointing at it.
 * {@code CreateQueue} is idempotent for identical attributes, so several instances racing
 * to create them is harmless.
 */
@Component
public class SqsQueues {

    private final SqsAsyncClient sqs;
    private final MessagingProperties properties;

    private volatile String queueUrl;
    private volatile String deadLetterQueueUrl;

    public SqsQueues(SqsAsyncClient sqs, MessagingProperties properties) {
        this.sqs = sqs;
        this.properties = properties;
    }

    public String queueUrl() {
        if (queueUrl == null) {
            resolve();
        }
        return queueUrl;
    }

    public String deadLetterQueueUrl() {
        if (deadLetterQueueUrl == null) {
            resolve();
        }
        return deadLetterQueueUrl;
    }

    private synchronized void resolve() {
        if (queueUrl != null && deadLetterQueueUrl != null) {
            return;
        }
        if (!properties.createQueues()) {
            deadLetterQueueUrl = sqs.getQueueUrl(r -> r.queueName(properties.deadLetterQueue())).join().queueUrl();
            queueUrl = sqs.getQueueUrl(r -> r.queueName(properties.queue())).join().queueUrl();
            return;
        }
        // Blocking joins: this runs once, on the relay's thread, before its first send.
        String dlqUrl = sqs.createQueue(r -> r.queueName(properties.deadLetterQueue())).join().queueUrl();
        String dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN).build())
                .join().attributes().get(QueueAttributeName.QUEUE_ARN);
        String redrive = "{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"%d\"}"
                .formatted(dlqArn, properties.maxReceiveCount());
        queueUrl = sqs.createQueue(r -> r.queueName(properties.queue())
                .attributes(Map.of(
                        QueueAttributeName.REDRIVE_POLICY, redrive,
                        QueueAttributeName.VISIBILITY_TIMEOUT,
                        Long.toString(properties.visibilityTimeout().toSeconds())))).join().queueUrl();
        deadLetterQueueUrl = dlqUrl;
    }
}

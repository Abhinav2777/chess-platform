package com.chessplatform.messaging.internal;

import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;

/**
 * SQS through Spring Cloud AWS 4.x (ADR-020). The client itself is auto-configured from
 * {@code spring.cloud.aws.*} — region, endpoint override for ElasticMQ, credentials — and
 * two things are set here on purpose.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class SqsSetup {

    /**
     * Explicit timeouts. The relay holds a database transaction open across the send, so a
     * hung SQS must fail that transaction in seconds, not after the SDK's defaults. The
     * listener's long polls (5.2) are bounded separately by their wait time.
     */
    @Bean
    SqsAsyncClientCustomizer sqsTimeouts() {
        return builder -> builder.overrideConfiguration(o -> o
                .apiCallAttemptTimeout(Duration.ofSeconds(25))
                .apiCallTimeout(Duration.ofSeconds(30)));
    }

    /**
     * Replaces the auto-configured template (which backs off for ours) to change one
     * default: {@link QueueNotFoundStrategy#FAIL}. The framework's default is to create a
     * missing queue on first send — which in AWS would silently create {@code game-events}
     * with no dead-letter policy, and every poison message would then retry forever. Queues
     * are created only by {@link SqsQueues}, with the redrive policy, or by Terraform.
     */
    @Bean
    SqsTemplate sqsTemplate(SqsAsyncClient sqs) {
        return SqsTemplate.builder()
                .sqsAsyncClient(sqs)
                .configure(options -> options.queueNotFoundStrategy(QueueNotFoundStrategy.FAIL))
                .build();
    }
}

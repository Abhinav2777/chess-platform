package com.chessplatform.integration.messaging;

import com.chessplatform.integration.IntegrationTestBase;
import com.chessplatform.messaging.Outbox;
import com.chessplatform.messaging.internal.OutboxRelay;
import com.chessplatform.messaging.internal.SqsQueues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relay against a real SQS-compatible server (ElasticMQ — see ADR-019 for why not
 * LocalStack), through Spring Cloud AWS's {@code SqsTemplate} (ADR-020). Also the proof
 * that the SDK's JSON protocol, and Spring Cloud AWS 4.1 on Boot 4.1 with Netty 4.2, work.
 */
@DisplayName("Outbox relay → SQS")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxRelayIntegrationTest extends IntegrationTestBase {

    static final GenericContainer<?> ELASTICMQ =
            new GenericContainer<>("softwaremill/elasticmq-native:1.7.1").withExposedPorts(9324);

    static {
        ELASTICMQ.start();
    }

    @DynamicPropertySource
    static void sqs(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.sqs.endpoint",
                () -> "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324));
        registry.add("spring.cloud.aws.credentials.access-key", () -> "local");
        registry.add("spring.cloud.aws.credentials.secret-key", () -> "local");
        registry.add("chess.messaging.create-queues", () -> "true");
    }

    @Autowired
    private Outbox outbox;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private SqsQueues queues;
    @Autowired
    private SqsAsyncClient sqs;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private io.awspring.cloud.sqs.operations.SqsTemplate template;
    @Autowired
    private JsonMapper json;

    @AfterEach
    void cleanUp() {
        jdbc().update("DELETE FROM outbox");
    }

    private UUID append(UUID aggregateId) {
        return transactions.execute(tx -> outbox.append("GAME_FINISHED", aggregateId, Map.of("result", "DRAW")));
    }

    /** Receives until {@code expected} messages arrive or a few empty polls in a row. */
    private List<Message> drain(int expected) {
        List<Message> received = new ArrayList<>();
        int emptyPolls = 0;
        while (received.size() < expected && emptyPolls < 3) {
            List<Message> batch = sqs.receiveMessage(r -> r.queueUrl(queues.queueUrl())
                    .maxNumberOfMessages(10).waitTimeSeconds(1)).join().messages();
            if (batch.isEmpty()) {
                emptyPolls++;
            }
            for (Message message : batch) {
                received.add(message);
                sqs.deleteMessage(r -> r.queueUrl(queues.queueUrl()).receiptHandle(message.receiptHandle())).join();
            }
        }
        return received;
    }

    @Test
    @Order(1)
    @DisplayName("sends a self-describing envelope and marks the row published")
    void publishes() {
        UUID gameId = UUID.randomUUID();
        UUID eventId = append(gameId);

        assertThat(relay.relayOnce()).isEqualTo(1);

        JsonNode envelope = json.readTree(drain(1).getFirst().body());
        assertThat(envelope.get("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(envelope.get("eventType").asString()).isEqualTo("GAME_FINISHED");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(gameId.toString());
        assertThat(envelope.get("payload").get("result").asString()).isEqualTo("DRAW");
        assertThat(jdbc().queryForObject("SELECT published_at IS NOT NULL FROM outbox WHERE id = ?",
                Boolean.class, eventId)).isTrue();
        assertThat(relay.relayOnce()).as("nothing left to send").isZero();
    }

    @Test
    @Order(2)
    @DisplayName("the main queue is created with a redrive policy to the dead-letter queue")
    void redrivePolicy() {
        String policy = sqs.getQueueAttributes(r -> r.queueUrl(queues.queueUrl())
                        .attributeNames(QueueAttributeName.REDRIVE_POLICY))
                .join().attributes().get(QueueAttributeName.REDRIVE_POLICY);

        assertThat(policy).contains("game-events-dlq").contains("3");
    }

    /**
     * Two relays (two instances) racing over one backlog. SKIP LOCKED gives them disjoint
     * rows, so with no crash in between, every event is sent exactly once.
     */
    @Test
    @Order(3)
    @DisplayName("concurrent relays send each of 30 events exactly once")
    void concurrentRelays() throws Exception {
        Set<UUID> appended = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            appended.add(append(UUID.randomUUID()));
        }

        List<Callable<Integer>> relays = List.of(this::relayUntilEmpty, this::relayUntilEmpty);
        List<Future<Integer>> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            results = pool.invokeAll(relays);
        }
        int claimed = results.get(0).get() + results.get(1).get();

        List<Message> received = drain(30);
        Set<String> ids = new HashSet<>();
        for (Message message : received) {
            ids.add(json.readTree(message.body()).get("eventId").asString());
        }
        assertThat(claimed).as("rows claimed across both relays").isEqualTo(30);
        assertThat(received).as("messages on the queue").hasSize(30);
        assertThat(ids).as("no duplicates, none missing")
                .containsExactlyInAnyOrderElementsOf(appended.stream().map(UUID::toString).toList());
    }

    private int relayUntilEmpty() {
        int total = 0;
        int n;
        while ((n = relay.relayOnce()) > 0) {
            total += n;
        }
        return total;
    }

    /**
     * The template must never create a queue. Spring Cloud AWS's default would, on first
     * send — and in AWS that queue would have no redrive policy, so poison messages would
     * retry forever instead of reaching the DLQ. SqsSetup configures FAIL.
     */
    @Test
    @Order(4)
    @DisplayName("the template refuses to create a missing queue")
    void neverCreatesQueues() {
        assertThatThrownBy(() -> template.send("no-such-queue", "hello"))
                .isInstanceOf(RuntimeException.class);

        assertThat(sqs.listQueues().join().queueUrls())
                .noneMatch(url -> url.endsWith("/no-such-queue"));
    }

    /** Last: stops the shared container. */
    @Test
    @Order(99)
    @DisplayName("with SQS down the event stays in the outbox, counted and explained")
    void sqsDown() {
        queues.queueUrl();   // resolved while up, as it would be in a running relay
        UUID eventId = append(UUID.randomUUID());
        ELASTICMQ.stop();

        relay.relayOnce();

        Map<String, Object> row = jdbc().queryForMap(
                "SELECT published_at, attempts, last_error FROM outbox WHERE id = ?", eventId);
        assertThat(row.get("published_at")).as("not lost, not marked").isNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).isNotBlank();
    }
}

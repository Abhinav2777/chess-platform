package com.chessplatform.integration.realtime;

import com.chessplatform.realtime.protocol.ClientMessage;
import com.chessplatform.realtime.protocol.Envelope;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A minimal protocol client for tests.
 *
 * <p>Frames land in a blocking queue, so tests <em>wait</em> for what they expect rather
 * than sleeping and hoping. A suite that sleeps is slow and flaky in equal measure, and
 * the flakiness always shows up in CI rather than locally.
 */
class TestWebSocketClient extends TextWebSocketHandler implements AutoCloseable {

    private final BlockingQueue<Envelope> received = new LinkedBlockingQueue<>();
    private final JsonMapper json;
    private WebSocketSession session;
    private CloseStatus closeStatus;

    TestWebSocketClient(JsonMapper json) {
        this.json = json;
    }

    TestWebSocketClient connect(int port) throws Exception {
        session = new StandardWebSocketClient()
                .execute(this, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
        return this;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        received.add(json.readValue(message.getPayload(), Envelope.class));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeStatus = status;
    }

    void send(ClientMessage type, Object payload) {
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(
                    new Envelope(Envelope.VERSION, type.name(), Instant.now(), payload))));
        } catch (Exception e) {
            throw new IllegalStateException("send failed", e);
        }
    }

    /**
     * Blocks until a frame of this type arrives, or fails the test.
     *
     * <p>Frames of other types are skipped, because the protocol legitimately interleaves
     * them — a presence announcement can arrive between a subscribe and a move, and a
     * test waiting for {@code MOVE_MADE} should not care.
     *
     * <p>{@code ERROR} and {@code AUTH_FAILED} are the exception: they fail immediately
     * and print their payload. Those two are almost always the actual problem, and
     * surfacing "ILLEGAL_MOVE: e2e5" beats "timed out waiting for MOVE_MADE", which sends
     * you looking in the wrong place entirely.
     */
    Envelope await(String type) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Envelope next = received.poll(500, TimeUnit.MILLISECONDS);
                if (next == null) {
                    continue;
                }
                if (next.type().equals(type)) {
                    return next;
                }
                if (next.type().equals("ERROR") || next.type().equals("AUTH_FAILED")) {
                    fail("expected %s but the server rejected the command: %s"
                            .formatted(type, next.payload()));
                }
                // Anything else is protocol chatter this test is not interested in.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + type);
            }
        }
        return fail("timed out waiting for " + type);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> payloadOf(Envelope envelope) {
        return (Map<String, Object>) envelope.payload();
    }

    void assertClosedWithin(long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline && (session.isOpen())) {
            Thread.sleep(50);
        }
        assertThat(session.isOpen()).as("session should have been closed by the server").isFalse();
    }

    /**
     * Waits for a presence frame about a particular user, skipping others.
     *
     * <p>Necessary because a client receives its own presence echo: pub/sub delivers to
     * every subscriber including the instance that published. Filtering by user id is what
     * a real client does too.
     */
    @SuppressWarnings("unchecked")
    Envelope awaitPresenceFor(UUID userId) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Envelope next = await("PLAYER_PRESENCE");
            Map<String, Object> payload = (Map<String, Object>) next.payload();
            if (userId.toString().equals(payload.get("userId"))) {
                return next;
            }
        }
        return fail("timed out waiting for a PLAYER_PRESENCE about " + userId);
    }

    private Envelope snapshot;

    /** Consumes the GAME_SNAPSHOT so a later await() does not race against it. */
    void captureSnapshot() {
        snapshot = await("GAME_SNAPSHOT");
    }

    Envelope lastSnapshot() {
        return snapshot != null ? snapshot : await("GAME_SNAPSHOT");
    }

    CloseStatus closeStatus() {
        return closeStatus;
    }

    boolean isOpen() {
        return session != null && session.isOpen();
    }

    /**
     * Declares no checked exception, deliberately.
     *
     * <p>{@code -Xlint:try} warns when an {@code AutoCloseable} can throw
     * {@code InterruptedException} from {@code close()}, and the warning is a real one:
     * try-with-resources turns an exception from {@code close()} into a <em>suppressed</em>
     * exception on whatever the body threw, so an interrupt raised during cleanup is
     * swallowed and the thread's interrupt status is never restored. The surrounding code
     * then carries on believing it was not interrupted.
     *
     * <p>Closing a socket that is already gone is also not a failure worth reporting —
     * the peer disappearing is the normal case in these tests.
     */
    @Override
    public void close() {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close();
        } catch (IOException alreadyGone) {
            // Expected when the server closed first, which several tests do on purpose.
        }
    }
}

package com.chessplatform.realtime.internal;

import com.chessplatform.common.error.DomainException;
import com.chessplatform.common.error.ErrorCode;
import com.chessplatform.realtime.protocol.Envelope;
import com.chessplatform.realtime.protocol.Payloads;
import com.chessplatform.realtime.protocol.ServerMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.Set;

/**
 * Serialises and writes frames.
 *
 * <h2>Jackson 3, not Jackson 2</h2>
 *
 * <p>Spring Boot 4 auto-configures a {@code tools.jackson.databind.json.JsonMapper}.
 * {@code com.fasterxml.jackson.databind.ObjectMapper} is the Jackson 2 type and has no
 * bean behind it — a mistake that compiles cleanly and fails at startup, which this
 * project has already made once in {@code SecurityConfig}.
 *
 * <p>Worth knowing: {@code JsonMapper} extends {@code ObjectMapper}, and the
 * auto-configuration only backs off for a {@code JsonMapper} bean. Declaring an
 * {@code @Bean ObjectMapper} leaves both in the context with the auto-configured one
 * primary, so the customisation is silently ignored.
 *
 * <p>Annotations are unaffected — {@code jackson-annotations} stayed on
 * {@code com.fasterxml.jackson.core}. Only the engine moved.
 *
 * <h2>Why sends never throw</h2>
 *
 * <p>A failed write means one client will miss one frame. It reconnects and receives a
 * snapshot (ADR-007), so the correct response is a debug line — not an exception that
 * propagates into the move pipeline and fails a move that was already committed.
 */
@Component
public class WebSocketSender {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSender.class);

    private final JsonMapper json;
    private final Validator validator;

    public WebSocketSender(JsonMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    public void send(WebSocketSession session, Envelope envelope) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(envelope)));
        } catch (Exception failed) {
            // Catching Exception rather than a specific type deliberately: Jackson 3
            // throws unchecked exceptions, sendMessage throws IOException, and a closed
            // socket can surface as either. All of them mean the same thing here.
            log.debug("Send failed on session {}: {}", session.getId(), failed.toString());
        }
    }

    public void sendError(WebSocketSession session, String code, String message) {
        send(session, Envelope.of(ServerMessage.ERROR, new Payloads.Failure(code, message)));
    }

    /**
     * Converts and validates an inbound payload: the socket's equivalent of
     * {@code @Valid @RequestBody}, against the same constraints the REST bodies carry.
     *
     * <p>Anything wrong with the client's frame is the client's error — VALIDATION_FAILED on the
     * socket, which stays open — and is refused before any transaction starts. Without this, a
     * missing game id reached {@code findById(null)} inside a read transaction and a non-UUID
     * failed in Jackson; both were logged at ERROR as unhandled and answered INTERNAL.
     */
    public <T> T parsePayload(Object rawPayload, Class<T> type) {
        T payload;
        try {
            payload = json.convertValue(rawPayload, type);
        } catch (RuntimeException malformed) {
            // Jackson 3 throws unchecked exceptions; any of them here means the client sent a
            // value of the wrong shape (a game id that is not a UUID, a string for a number).
            throw new DomainException.Rejected(ErrorCode.VALIDATION_FAILED,
                    "Malformed " + type.getSimpleName() + " payload.");
        }
        if (payload == null) {
            throw new DomainException.Rejected(ErrorCode.VALIDATION_FAILED,
                    type.getSimpleName() + " needs a payload.");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            // One field, deterministically: the first by name. Enough for a client to fix its
            // frame, and stable for tests.
            ConstraintViolation<T> first = violations.stream()
                    .min(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                    .orElseThrow();
            throw new DomainException.Rejected(ErrorCode.VALIDATION_FAILED,
                    type.getSimpleName() + "." + first.getPropertyPath() + " " + first.getMessage());
        }
        return payload;
    }

    public Envelope parseEnvelope(String frame) {
        return json.readValue(frame, Envelope.class);
    }

    /** Serialises for the fanout transport. Same encoding as the wire format, by design. */
    public String serialise(Envelope envelope) {
        return json.writeValueAsString(envelope);
    }
}

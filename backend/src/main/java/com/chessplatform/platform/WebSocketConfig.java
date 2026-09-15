package com.chessplatform.platform;

import com.chessplatform.realtime.internal.ChessWebSocketHandler;
import com.chessplatform.realtime.internal.RealtimeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(RealtimeProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChessWebSocketHandler handler;
    private final RealtimeProperties properties;

    public WebSocketConfig(ChessWebSocketHandler handler, RealtimeProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(concurrencySafe(handler), "/ws")
                // From configuration, not hardcoded: the frontend origin differs between
                // local, staging and production. A WebSocket handshake is not subject to
                // the same-origin policy the way XHR is — any page anywhere can attempt
                // one — so this is the only thing constraining who may try. It is not
                // sufficient alone, which is why the protocol still authenticates in its
                // first frame.
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }

    /**
     * Wraps every session so concurrent writes are serialised and bounded.
     *
     * <p>Two distinct problems, one decorator.
     *
     * <p><strong>Thread safety.</strong> {@code WebSocketSession} is not safe for
     * concurrent writes. Frames arrive on container threads while event fanout writes from
     * others, so two simultaneous sends can interleave and produce a corrupt frame — which
     * surfaces on the client as an unparseable message, at load, intermittently, and
     * never in testing.
     *
     * <p><strong>Backpressure.</strong> A client that has stopped reading — a phone that
     * lost signal but whose TCP connection has not failed yet — would otherwise make the
     * server buffer without limit. The send-buffer cap closes such a session instead. One
     * slow consumer must not be able to exhaust the heap, and it will reconnect and
     * receive a snapshot (ADR-007).
     *
     * <h2>Why the decorated session is remembered</h2>
     *
     * <p>Wrapping only in {@code afterConnectionEstablished} is the obvious mistake and a
     * subtle one: every <em>later</em> callback is handed the original session by the
     * container, so the handler's own sends would bypass the decorator entirely. Half the
     * writes would be protected and half would not — which is worse than none, because it
     * looks correct. The map keeps the wrapper for the life of the connection.
     */
    private WebSocketHandler concurrencySafe(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {

            private final Map<String, WebSocketSession> decorated = new ConcurrentHashMap<>();

            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                WebSocketSession wrapper = new ConcurrentWebSocketSessionDecorator(
                        session,
                        (int) properties.sendTimeout().toMillis(),
                        properties.sendBufferBytes());
                decorated.put(session.getId(), wrapper);
                super.afterConnectionEstablished(wrapper);
            }

            @Override
            public void handleMessage(WebSocketSession session, WebSocketMessage<?> message)
                    throws Exception {
                super.handleMessage(wrapperFor(session), message);
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception)
                    throws Exception {
                super.handleTransportError(wrapperFor(session), exception);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
                    throws Exception {
                WebSocketSession wrapper = decorated.remove(session.getId());
                super.afterConnectionClosed(wrapper != null ? wrapper : session, status);
            }

            private WebSocketSession wrapperFor(WebSocketSession session) {
                // Falls back to the raw session rather than failing: a missing wrapper
                // means a lifecycle we did not anticipate, and dropping the connection
                // would be a worse response than an unprotected write.
                return decorated.getOrDefault(session.getId(), session);
            }
        };
    }
}

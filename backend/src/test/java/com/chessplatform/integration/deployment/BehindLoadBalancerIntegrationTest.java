package com.chessplatform.integration.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployed shape (ADR-023): one origin serving the SPA, the API and the socket, behind a
 * load balancer, over plain HTTP.
 *
 * <p>The test client plays the ALB: it connects from 127.0.0.1, which this class trusts as a
 * proxy in place of the VPC range, and sets {@code X-Forwarded-For} the way the ALB does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
        "chess.auth.refresh-cookie-secure=false"})
@ActiveProfiles("aws")
@DisplayName("Behind the load balancer")
class BehindLoadBalancerIntegrationTest extends DeploymentTestSupport {

    @Test
    @DisplayName("the SPA is served at / without a token; the API behind it is still deny-by-default")
    void servesTheSpaAndNothingMore() throws Exception {
        HttpResponse<String> page = get("/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("test-spa");
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/html"));

        assertThat(get("/assets/missing.js").statusCode())
                .as("an asset path is open, so a missing file is 404 — not 401").isEqualTo(404);
        assertThat(get("/api/games").statusCode()).isEqualTo(401);
        assertThat(postJson("/", "{}", null).statusCode())
                .as("only GET is opened for the page").isEqualTo(401);
    }

    /**
     * Without forwarded-header handling every request would come from the ALB's address and
     * share one bucket: ten failed logins anywhere would lock out everyone.
     */
    @Test
    @DisplayName("per-IP limits apply to the client's address, not the load balancer's")
    void rateLimitsTheForwardedClient() throws Exception {
        for (int i = 0; i < 10; i++) {   // login-ip: 10 per minute; distinct users dodge login-user
            assertThat(failedLogin("user" + i, "203.0.113.10")).isEqualTo(401);
        }
        assertThat(failedLogin("user10", "203.0.113.10")).isEqualTo(429);

        assertThat(failedLogin("user11", "203.0.113.20"))
                .as("another client behind the same load balancer is unaffected").isEqualTo(401);
    }

    /**
     * The ALB appends the address it saw to whatever X-Forwarded-For the client sent. Tomcat
     * reads from the right and stops at the first untrusted entry, so a prefix the client
     * wrote itself is never reached.
     */
    @Test
    @DisplayName("a client cannot escape its bucket by writing its own X-Forwarded-For")
    void spoofedPrefixIsIgnored() throws Exception {
        for (int i = 0; i < 10; i++) {
            failedLogin("user" + i, "203.0.113.10");
        }

        assertThat(failedLogin("user10", "198.51.100.1, 203.0.113.10")).isEqualTo(429);
    }

    @Test
    @DisplayName("over plain HTTP the refresh cookie drops Secure, and keeps HttpOnly and SameSite")
    void cookieWithoutSecure() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/register", """
                {"username":"plainhttp","email":"p@example.com","password":"correct-horse-battery"}
                """, "203.0.113.30");

        assertThat(response.statusCode()).isEqualTo(201);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).startsWith("refresh_token=").contains("HttpOnly", "SameSite=Strict")
                .doesNotContain("Secure");
    }

    /**
     * The allow-list (chess.realtime.allowed-origins) names only the dev server. The page's
     * own origin needs no entry — Spring's handshake interceptor admits same-origin requests
     * before consulting it — which is why the ALB hostname, unknown until apply, need not be
     * configured anywhere.
     */
    @Test
    @DisplayName("the socket accepts its own origin without configuration, and refuses others")
    void socketOrigin() throws Exception {
        try (WebSocketSession session = openSocket("http://localhost:" + port)) {
            assertThat(session.isOpen()).isTrue();
        }

        assertThatThrownBy(() -> openSocket("http://evil.example"))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("403");
    }

    private WebSocketSession openSocket(String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler(), headers, URI.create("ws://localhost:" + port + "/ws"))
                .get(5, TimeUnit.SECONDS);
    }
}

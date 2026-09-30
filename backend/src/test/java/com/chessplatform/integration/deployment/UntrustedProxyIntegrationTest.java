package com.chessplatform.integration.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code aws} profile exactly as shipped: only the VPC range is a trusted proxy. The test
 * client connects from 127.0.0.1 — outside it — so it stands for anything that reaches the
 * task without passing through the ALB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("aws")
@DisplayName("Forwarded headers from an untrusted peer")
class UntrustedProxyIntegrationTest extends DeploymentTestSupport {

    @Test
    @DisplayName("are ignored: a fresh X-Forwarded-For per request does not buy a fresh bucket")
    void untrustedForwardedForIsIgnored() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(failedLogin("user" + i, "203.0.113." + i)).isEqualTo(401);
        }

        assertThat(failedLogin("user10", "203.0.113.99"))
                .as("all eleven counted against the real peer address").isEqualTo(429);
    }

    @Test
    @DisplayName("the refresh cookie is Secure unless a deployment says otherwise")
    void cookieSecureByDefault() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/register", """
                {"username":"defaults","email":"d@example.com","password":"correct-horse-battery"}
                """, null);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow()).contains("Secure");
    }
}

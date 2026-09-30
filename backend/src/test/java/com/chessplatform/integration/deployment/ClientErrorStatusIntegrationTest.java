package com.chessplatform.integration.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client's mistake is a 4xx, not a 500 with an ERROR log. Found in 7.1: the catch-all
 * {@code @ExceptionHandler(Exception.class)} was also catching Spring MVC's own exceptions,
 * which carry their proper status — so malformed JSON, a bad path variable, a wrong method
 * and a missing static file were all "Internal error", and each logged a stack trace that an
 * alarm on ERROR would page on.
 *
 * <p>Same annotations as {@link UntrustedProxyIntegrationTest}, so the Spring context is reused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("aws")
@DisplayName("Client errors")
class ClientErrorStatusIntegrationTest extends DeploymentTestSupport {

    @Test
    @DisplayName("a missing static file is 404")
    void missingAsset() throws Exception {
        assertThat(get("/assets/missing.js").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("malformed JSON is 400 problem+json")
    void malformedJson() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/login", "{not json", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
    }

    @Test
    @DisplayName("an unsupported method is 405")
    void wrongMethod() throws Exception {
        assertThat(get("/api/auth/login").statusCode()).isEqualTo(405);
    }

    @Test
    @DisplayName("a path variable that is not a UUID is 400")
    void badPathVariable() throws Exception {
        HttpResponse<String> registered = postJson("/api/auth/register", """
                {"username":"pathcheck","email":"pc@example.com","password":"correct-horse-battery"}
                """, null);
        String token = registered.body().replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/games/not-a-uuid"))
                        .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }
}

package com.chessplatform.integration.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The findings of the Phase 10.1 security review (docs/security-review.md), each held by a test
 * over real HTTP — so the filter, the headers and the handlers are the ones the container runs.
 * Every case here was reproduced against the running application first.
 *
 * <p>Same annotations as {@link ClientErrorStatusIntegrationTest}, so the Spring context is reused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("aws")
@DisplayName("Security review findings")
class SecurityReviewIntegrationTest extends DeploymentTestSupport {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("API4: a body over the limit is 413 before anything parses it — even unauthenticated")
    void oversizedBody() throws Exception {
        // Was: fully read and parsed, then 401. 100 MB of it cost ~600 MB of heap.
        String body = "{\"username\":\"x\",\"password\":\"" + "a".repeat(20_000) + "\"}";

        HttpResponse<String> response = postJson("/api/auth/login", body, null);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"status\":413");
    }

    @Test
    @DisplayName("a password of 72 characters but over 72 bytes is 400 VALIDATION_FAILED, not 500")
    void passwordOverBcryptBytes() throws Exception {
        // 20 emoji: 20 characters (passes @Size), 80 bytes (bcrypt refuses past 72).
        String name = "pw" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = postJson("/api/auth/register", """
                {"username":"%s","email":"%s@example.com","password":"%s"}
                """.formatted(name, name, "😀".repeat(20)), null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_FAILED").contains("password");
    }

    @Test
    @DisplayName("a move from a square to itself is 422 ILLEGAL_MOVE, not 500")
    void moveToSameSquare() throws Exception {
        Players players = twoPlayersAndAGame();

        HttpResponse<String> response = send("POST", "/api/games/" + players.gameId + "/moves", """
                {"clientMoveId":"%s","expectedPly":0,"from":"e2","to":"e2"}
                """.formatted(UUID.randomUUID()), players.whiteToken);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("ILLEGAL_MOVE");
    }

    @Test
    @DisplayName("API1: someone who is not a player cannot read a game — the rule SUBSCRIBE enforces")
    void strangerCannotReadGame() throws Exception {
        Players players = twoPlayersAndAGame();
        String stranger = register("st");

        HttpResponse<String> asStranger = send("GET", "/api/games/" + players.gameId, null, stranger);
        HttpResponse<String> asPlayer = send("GET", "/api/games/" + players.gameId, null, players.whiteToken);

        assertThat(asStranger.statusCode()).isEqualTo(422);
        assertThat(asStranger.body()).contains("NOT_A_PLAYER").doesNotContain("legalMoves");
        assertThat(asPlayer.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("every response carries a same-origin Content-Security-Policy and a Referrer-Policy")
    void securityHeaders() throws Exception {
        HttpResponse<String> response = get("/api/users/me");

        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(csp -> assertThat(csp)
                        .contains("default-src 'self'", "script-src 'self'", "frame-ancestors 'none'")
                        .doesNotContain("unsafe-inline", "unsafe-eval"));
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("same-origin");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
    }

    private record Players(String whiteToken, String gameId) {
    }

    private Players twoPlayersAndAGame() throws Exception {
        String white = register("w");
        String blackName = "b" + UUID.randomUUID().toString().substring(0, 8);
        registerAs(blackName);
        HttpResponse<String> created = send("POST", "/api/games", """
                {"opponentUsername":"%s","playAs":"WHITE","initialSeconds":600,"incrementSeconds":0}
                """.formatted(blackName), white);
        assertThat(created.statusCode()).isEqualTo(201);
        return new Players(white, created.body().replaceAll(".*?\"id\":\"([^\"]+)\".*", "$1"));
    }

    private String register(String prefix) throws Exception {
        return registerAs(prefix + UUID.randomUUID().toString().substring(0, 8));
    }

    private String registerAs(String name) throws Exception {
        HttpResponse<String> response = postJson("/api/auth/register", """
                {"username":"%s","email":"%s@example.com","password":"correct-horse-battery"}
                """.formatted(name, name), null);
        assertThat(response.statusCode()).isEqualTo(201);
        return response.body().replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private HttpResponse<String> send(String method, String path, String json, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                                             : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

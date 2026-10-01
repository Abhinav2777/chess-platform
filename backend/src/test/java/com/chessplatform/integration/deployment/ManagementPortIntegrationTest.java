package com.chessplatform.integration.deployment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator lives on its own port in AWS (ADR-023, 7.4). The ALB forwards only the application
 * port, so nothing under /actuator is reachable from outside the VPC; the ALB's health check,
 * and later metrics scraping, use the management port directly.
 *
 * <p>Before this, any signed-in player could read /actuator/prometheus through the ALB, and
 * /actuator/health listed every component with its details.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("aws")
@DisplayName("Management port")
class ManagementPortIntegrationTest extends DeploymentTestSupport {

    @LocalManagementPort
    int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("the application port serves no actuator endpoint, even to a signed-in user")
    void actuatorNotOnTheApplicationPort() throws Exception {
        String token = register("mgmtcheck");

        assertThat(send(port, "/actuator/prometheus", token).statusCode()).isEqualTo(404);
        assertThat(send(port, "/actuator/metrics", token).statusCode()).isEqualTo(404);
        assertThat(send(port, "/actuator/health/readiness", null).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the management port answers readiness with a status and no component details")
    void readinessOnTheManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);

        HttpResponse<String> readiness = send(managementPort, "/actuator/health/readiness", null);
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"")
                .doesNotContain("components").doesNotContain("details");

        assertThat(send(managementPort, "/actuator/health", null).body())
                .as("no database product, no Valkey version").doesNotContain("components");
    }

    /**
     * The tests above run the management server on a random port (DeploymentTestSupport), so on
     * their own they would pass whatever the profile says. This pins the profile itself.
     */
    @Test
    @DisplayName("the aws profile puts actuator on 8081 and hides health details")
    void profileDeclaresIt() throws Exception {
        var sources = new YamlPropertySourceLoader()
                .load("aws", new ClassPathResource("application-aws.yml"));

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.getProperty("management.server.port")).hasToString("8081");
            assertThat(source.getProperty("management.endpoint.health.show-details")).hasToString("never");
        });
    }

    /** The k8s profile (Phase 8) makes the same promise: probes on 8081, nothing behind the Service. */
    @Test
    @DisplayName("the k8s profile puts actuator on 8081 and hides health details")
    void k8sProfileDeclaresIt() throws Exception {
        var sources = new YamlPropertySourceLoader()
                .load("k8s", new ClassPathResource("application-k8s.yml"));

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.getProperty("management.server.port")).hasToString("8081");
            assertThat(source.getProperty("management.endpoint.health.show-details")).hasToString("never");
            assertThat(source.getProperty("server.forward-headers-strategy")).hasToString("native");
        });
    }

    private String register(String username) throws Exception {
        HttpResponse<String> response = postJson("/api/auth/register", """
                {"username":"%s","email":"%s@example.com","password":"correct-horse-battery"}
                """.formatted(username, username), null);
        assertThat(response.statusCode()).isEqualTo(201);
        return response.body().replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");
    }

    private HttpResponse<String> send(int targetPort, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + targetPort + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

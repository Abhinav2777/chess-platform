package com.chessplatform.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When is actuator open without credentials? Only on its own, network-isolated port. Sharing the
 * application port (the local profile) must keep everything but health behind authentication.
 */
@DisplayName("Actuator on its own port")
class ActuatorPortTest {

    @Test
    @DisplayName("no management port configured: shares the application port — stays protected")
    void unset() {
        assertThat(SecurityConfig.actuatorOnItsOwnPort(new MockEnvironment())).isFalse();
    }

    @Test
    @DisplayName("8081 beside the default 8080 (aws, k8s): its own port")
    void separate() {
        assertThat(SecurityConfig.actuatorOnItsOwnPort(new MockEnvironment()
                .withProperty("management.server.port", "8081"))).isTrue();
    }

    @Test
    @DisplayName("the same number as the server: one port — stays protected")
    void same() {
        assertThat(SecurityConfig.actuatorOnItsOwnPort(new MockEnvironment()
                .withProperty("management.server.port", "9000").withProperty("server.port", "9000"))).isFalse();
    }

    @Test
    @DisplayName("0 (random, as in tests): always a port of its own, even beside server.port=0")
    void random() {
        assertThat(SecurityConfig.actuatorOnItsOwnPort(new MockEnvironment()
                .withProperty("management.server.port", "0").withProperty("server.port", "0"))).isTrue();
    }

    @Test
    @DisplayName("-1 (management server disabled): nothing separate to open")
    void disabled() {
        assertThat(SecurityConfig.actuatorOnItsOwnPort(new MockEnvironment()
                .withProperty("management.server.port", "-1"))).isFalse();
    }
}

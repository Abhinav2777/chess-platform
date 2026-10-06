package com.chessplatform.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On AWS, a missing signing key must stop startup — never fall back to application.yml's
 * development key, which is published in this repository (10.1). The aws profile therefore
 * references the variable with no default; this pins it, because adding ":something" back is a
 * one-character change that every other test would pass.
 */
@DisplayName("AWS signing key")
class AwsSigningKeyConfigTest {

    @Test
    @DisplayName("the aws profile takes the JWT secret from the environment, with no default")
    void noFallback() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("aws", new ClassPathResource("application-aws.yml"));

        Object secret = sources.getFirst().getProperty("chess.auth.jwt-secret");

        assertThat(secret).hasToString("${CHESS_AUTH_JWT_SECRET}");
    }
}

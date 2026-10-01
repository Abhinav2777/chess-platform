package com.chessplatform.platform;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Separates two Valkey time budgets that Lettuce, by default, makes one.
 *
 * <p>{@code spring.data.redis.timeout} (1 s, ADR-004) is meant for <em>commands</em>: no request
 * may stall on Valkey for long. But Lettuce also uses it to bound <em>connection
 * initialisation</em> — TCP, TLS handshake, HELLO — which happens once per connection. The first
 * ECS deployment (7.5) died there: on a 0.25-vCPU Fargate task the first TLS handshake to
 * ElastiCache took over a second, so every task failed at startup with "Connection
 * initialization timed out after 1 second(s)". Reproduced locally by
 * {@code ValkeyTimeoutsIntegrationTest} through a proxy that answers two seconds late.
 *
 * <p>So: the client-level timeout — what the handshake waits for — becomes
 * {@code chess.valkey.connection-init-timeout}, and every command is expired separately after
 * {@code spring.data.redis.timeout} by {@link TimeoutOptions}. A command still fails in a second;
 * a slow handshake no longer kills the process.
 */
@Configuration(proxyBeanMethods = false)
class ValkeyClientConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer separateHandshakeFromCommandTimeout(
            DataRedisProperties redis,
            @Value("${chess.valkey.connection-init-timeout:10s}") Duration connectionInitTimeout) {
        Duration commandTimeout = redis.getTimeout() != null ? redis.getTimeout() : Duration.ofSeconds(1);
        Duration connectTimeout = redis.getConnectTimeout() != null ? redis.getConnectTimeout() : Duration.ofSeconds(10);

        return builder -> builder
                // RedisURI timeout: bounds connection initialisation (and is the fallback for
                // anything TimeoutOptions does not cover).
                .commandTimeout(connectionInitTimeout)
                // Replaces the ClientOptions Boot built, so its one setting is carried over:
                // the TCP connect timeout (spring.data.redis.connect-timeout).
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                        .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                        .build());
    }
}

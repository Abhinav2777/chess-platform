package com.chessplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for both deployables.
 *
 * <p>The API and the async workers run from this same artifact, distinguished only by
 * the active Spring profile ({@code worker} enables the SQS consumers and disables the
 * web tier). They are separate deployments — separate ECS tasks, separate Kubernetes
 * Deployments — so they scale independently, but they are one codebase. See ADR-001.
 */
@SpringBootApplication
public class ChessPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChessPlatformApplication.class, args);
    }
}

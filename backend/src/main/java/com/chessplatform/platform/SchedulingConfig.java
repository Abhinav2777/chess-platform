package com.chessplatform.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled}, currently for the timeout sweeper.
 *
 * <p>Spring's default scheduler is a <strong>single thread</strong>. That is deliberate
 * here — the sweeper is the only scheduled job and a single thread cannot run two sweeps
 * concurrently, which is one fewer race to reason about. When a second job is added, this
 * becomes a bottleneck and needs an explicit pool: a slow job would otherwise delay every
 * other job on the same thread.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

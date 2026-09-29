package com.chessplatform.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Ends the process once the {@code migrate} role has done its job.
 *
 * <p>By the time runners execute, Flyway has migrated (it runs during context start) and
 * Hibernate has validated the schema against the entities. Without this, the process would
 * stay up — pools and scheduler threads keep the JVM alive — and a one-off deploy step would
 * never report completion. Exit code 0 means "migrated and valid"; any failure earlier in
 * startup exits non-zero on its own, which is what stops the deploy.
 */
@Component
@Profile("migrate")
public class MigrateAndExit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrateAndExit.class);

    private final ConfigurableApplicationContext context;

    public MigrateAndExit(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Migrations applied and schema validated; exiting");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}

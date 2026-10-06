package com.chessplatform.platform.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration
@EnableConfigurationProperties(PasswordHashingProperties.class)
public class PasswordEncoderConfig {

    private static final Logger log = LoggerFactory.getLogger(PasswordEncoderConfig.class);

    /**
     * bcrypt with cost factor 12.
     *
     * <h2>Why 12</h2>
     *
     * <p>The cost is a work factor: each increment doubles the time to hash. It is
     * deliberately expensive, because the thing being defended against is an attacker
     * who has already stolen the database and is grinding candidates offline. Spring's
     * default of 10 dates from hardware two decades old. 12 lands around 200–300 ms on a
     * typical modern core — high enough to make offline cracking costly, low enough that
     * a login is not perceptibly slow.
     *
     * <p>It is also a denial-of-service surface, which is the part people forget: an
     * unauthenticated endpoint that burns 250 ms of CPU per request is a lever. That is
     * why login is rate-limited (Phase 4) rather than relying on the hash cost alone.
     *
     * <h2>Why DelegatingPasswordEncoder for a single algorithm</h2>
     *
     * <p>It stores the algorithm as a prefix — {@code {bcrypt}$2a$12$...} — so the hash
     * is self-describing. Without it, changing algorithm or cost later means every
     * existing hash is unidentifiable and every user has to reset their password. With
     * it, adding an entry to this map and changing the default id is enough: old hashes
     * still verify against their original algorithm, and
     * {@code PasswordEncoder.upgradeEncoding} tells us when to rehash on next login.
     *
     * <p>That prefix is why {@code users.password_hash} is {@code VARCHAR(100)} rather
     * than the 60 characters raw bcrypt needs.
     *
     * <p>Argon2id is the stronger modern choice and is a one-line addition here, but it
     * requires a Bouncy Castle dependency. Not worth adding a coordinate for a marginal
     * gain at this stage; bcrypt at cost 12 is not a weak position.
     */
    @Bean
    public PasswordEncoder passwordEncoder(PasswordHashingProperties hashing, MeterRegistry metrics) {
        String defaultEncoderId = "bcrypt";
        Map<String, PasswordEncoder> encoders = Map.of(
                defaultEncoderId, new BCryptPasswordEncoder(12));
        PasswordEncoder bcrypt = new DelegatingPasswordEncoder(defaultEncoderId, encoders);

        // Bounded, on platform threads: see BoundedPasswordEncoder (Phase 9.4). The processor
        // count is logged because it is the number the whole argument rests on — on a fractional
        // vCPU it is 1, and that is invisible from the task definition.
        int processors = Runtime.getRuntime().availableProcessors();
        int threads = hashing.effectiveThreads(processors);
        log.info("Password hashing: {} platform thread(s), queue {}; JVM sees {} processor(s)",
                threads, hashing.queueCapacity(), processors);
        return new BoundedPasswordEncoder(bcrypt, threads, hashing.queueCapacity(), metrics);
    }
}

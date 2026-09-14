package com.chessplatform.common.id;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Uuid7")
class Uuid7Test {

    @Test
    @DisplayName("sets the RFC 9562 version and variant bits")
    void encodesVersionAndVariant() {
        UUID id = Uuid7.generate();

        assertThat(id.version()).isEqualTo(7);
        // Variant 2 is the RFC 4122/9562 layout (bit pattern 10xx).
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("embeds the generation timestamp in the high 48 bits")
    void embedsTimestamp() {
        long before = System.currentTimeMillis();
        UUID id = Uuid7.generate();
        long after = System.currentTimeMillis();

        assertThat(Uuid7.timestampOf(id)).isBetween(before, after);
    }

    /**
     * The property the whole class exists for. If this fails, the index-locality
     * argument in the class comment is false and we may as well use randomUUID().
     */
    @Test
    @DisplayName("sorts chronologically when compared as unsigned bytes")
    void isLexicographicallyOrdered() {
        List<UUID> generated = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            generated.add(Uuid7.generate());
        }

        List<String> asStrings = generated.stream().map(UUID::toString).toList();
        assertThat(asStrings).isSorted();
    }

    /**
     * Ordering must hold for IDs minted inside a single millisecond too, otherwise
     * high-throughput inserts still scatter across the index. This is what rand_a
     * being used as a counter buys us.
     */
    @Test
    @DisplayName("stays ordered within a single millisecond")
    void isOrderedWithinOneMillisecond() {
        long fixedTimestamp = 1_757_000_000_000L;

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ids.add(Uuid7.generate(fixedTimestamp).toString());
        }

        assertThat(ids).isSorted();
        assertThat(Set.copyOf(ids)).hasSize(500);
    }

    /**
     * NTP correction or a VM migration can move the wall clock backwards. Emitting a
     * smaller timestamp would break ordering permanently for every row inserted during
     * the regression, so the generator must hold its previous value instead.
     */
    @Test
    @DisplayName("never moves backwards when the clock regresses")
    void survivesClockRegression() {
        long now = 1_757_000_000_000L;

        String beforeJump = Uuid7.generate(now).toString();
        String afterJump = Uuid7.generate(now - 5_000).toString();   // clock steps back 5s
        String afterRecovery = Uuid7.generate(now).toString();

        assertThat(afterJump).isGreaterThan(beforeJump);
        assertThat(afterRecovery).isGreaterThan(afterJump);
    }

    @Test
    @DisplayName("produces no duplicates under concurrent generation")
    void isThreadSafe() throws Exception {
        int threads = 16;
        int perThread = 2_000;
        var results = new ConcurrentLinkedQueue<UUID>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            results.add(Uuid7.generate());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        Set<UUID> unique = results.stream().collect(Collectors.toSet());
        assertThat(results).hasSize(threads * perThread);
        assertThat(unique).hasSize(threads * perThread);
    }
}

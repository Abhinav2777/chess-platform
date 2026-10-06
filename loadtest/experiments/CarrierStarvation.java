// Phase 9.4 experiment: does CPU-bound bcrypt on virtual threads delay unrelated requests?
//
// Probes arrive every 20 ms, each a virtual thread that waits 5 ms for "a database reply"; the
// measure is how late it finishes, counted from its arrival. Meanwhile a burst of bcrypt-12 hashes
// runs either on virtual threads (registration before 9.4) or on one platform thread
// (BoundedPasswordEncoder). Run pinned to one CPU, as the JVM sees a 0.5-vCPU Fargate task:
//
//   CP=$(find ~/.gradle/caches/modules-2 -name 'spring-security-crypto-7.1.1.jar' | head -1)
//   CL=$(find ~/.gradle/caches/modules-2 -name 'commons-logging-*.jar' ! -name '*sources*' | head -1)
//   SC=$(find ~/.gradle/caches/modules-2 -name 'spring-core-7*.jar' ! -name '*sources*' | head -1)
//   taskset -c 3 java -XX:ActiveProcessorCount=1 -cp "$CP:$CL:$SC" CarrierStarvation.java virtual 8
//   taskset -c 3 java -XX:ActiveProcessorCount=1 -cp "$CP:$CL:$SC" CarrierStarvation.java platform 8
//
// Results: docs/perf/optimisation-02.md.
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import java.util.concurrent.*;

/**
 * Does CPU-bound bcrypt on virtual threads delay unrelated virtual threads waiting on I/O?
 * Probes: every 20 ms a virtual thread "waits for a DB reply" (sleep 5 ms); lateness is measured
 * from the probe's arrival, so time spent waiting for a carrier to start counts too. Load: HASHES bcrypt-12 hashes submitted at once, either on virtual threads
 * (as before the fix) or on a pool of 1 platform thread (BoundedPasswordEncoder).
 */
public class CarrierStarvation {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int hashes = Integer.parseInt(args[1]);
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
        bcrypt.encode("warm-up");
        long t0 = System.nanoTime(); bcrypt.encode("timing"); long hashMs = (System.nanoTime() - t0) / 1_000_000;

        ExecutorService hashing = mode.equals("virtual")
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(1, Thread.ofPlatform().daemon(true).factory());
        List<Long> lateness = Collections.synchronizedList(new ArrayList<>());
        List<Thread> probes = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(hashes);
        for (int i = 0; i < hashes; i++) hashing.submit(() -> { bcrypt.encode("pw"); done.countDown(); });

        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            long start = System.nanoTime();  // when the request arrives, not when it first runs
            probes.add(Thread.ofVirtual().start(() -> {
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                lateness.add((System.nanoTime() - start) / 1_000_000 - 5);
            }));
            Thread.sleep(20);
        }
        for (Thread p : probes) p.join();
        done.await();
        List<Long> s = new ArrayList<>(lateness); Collections.sort(s);
        System.out.printf("%-8s carriers=%s processors=%d hash=%dms hashes=%d probes=%d  wake-up lateness ms: p50=%d p95=%d p99=%d max=%d%n",
                mode, System.getProperty("jdk.virtualThreadScheduler.parallelism", "default"), Runtime.getRuntime().availableProcessors(), hashMs, hashes, s.size(),
                s.get(s.size() / 2), s.get((int) (s.size() * 0.95)), s.get((int) (s.size() * 0.99)), s.get(s.size() - 1));
    }
}

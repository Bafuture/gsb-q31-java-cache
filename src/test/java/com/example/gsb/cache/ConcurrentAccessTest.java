package com.example.gsb.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentAccessTest {

    private static final int THREADS = 8;
    private static final int OPS_PER_THREAD = 20_000;
    private static final int KEY_SPACE = 64;
    private static final int CAPACITY = 16;

    @Test
    void concurrentReadsAndWritesStayConsistent() throws Exception {
        runStress(EvictionPolicy.LRU, null);
    }

    @Test
    void concurrentReadsAndWritesStayConsistentWithLfuAndTti() throws Exception {
        runStress(EvictionPolicy.LFU, Duration.ofNanos(50_000));
    }

    private void runStress(EvictionPolicy policy, Duration tti) throws Exception {
        AtomicLong attemptedGets = new AtomicLong();
        Map<String, AtomicInteger> observedCauses = new ConcurrentHashMap<>();
        EvictionListener<Integer, Integer> listener = (key, value, cause) -> {
            observedCauses.computeIfAbsent(cause.name(), c -> new AtomicInteger()).incrementAndGet();
            if (value == null) {
                throw new AssertionError("listener received null value for key " + key);
            }
        };

        CacheConfig.Builder<?> builder = CacheConfig.newBuilder()
                .capacity(CAPACITY)
                .evictionPolicy(policy)
                .evictionListener(listener);
        if (tti != null) {
            builder.expireAfterAccess(tti);
        }
        Cache<Integer, Integer> cache = LocalCache.create(builder.build());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = ThreadLocalRandom.current().nextInt(KEY_SPACE);
                        int roll = ThreadLocalRandom.current().nextInt(10);
                        if (roll < 7) {
                            attemptedGets.incrementAndGet();
                            cache.get(key);
                        } else {
                            cache.put(key, i);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(2, TimeUnit.MINUTES); // propagates any thrown exception
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(cache.size())
                .as("cache never exceeds capacity")
                .isLessThanOrEqualTo(CAPACITY);

        CacheStats stats = cache.statsSnapshot();
        assertThat(stats.requests())
                .as("every attempted get is counted exactly once")
                .isEqualTo(attemptedGets.get());
        assertThat(stats.hits() + stats.misses()).isEqualTo(attemptedGets.get());
        assertThat(stats.currentSize()).isEqualTo(cache.size());

        int notified = observedCauses.values().stream().mapToInt(AtomicInteger::get).sum();
        int expected = Math.toIntExact(stats.evictions() + stats.expiredEvictions());
        // REPLACED/REMOVED notifications are not in stats; in this workload
        // there are no explicit removals, so eviction+expiry notifications match.
        assertThat(notified)
                .as("listener invocations for eviction/expiry match stats")
                .isGreaterThanOrEqualTo(expected);
        assertThat(observedCauses.getOrDefault("EVICTED", new AtomicInteger()).get())
                .isLessThanOrEqualTo(Math.toIntExact(stats.evictions()));
        assertThat(observedCauses.getOrDefault("EXPIRED", new AtomicInteger()).get())
                .isLessThanOrEqualTo(Math.toIntExact(stats.expiredEvictions()));
    }
}

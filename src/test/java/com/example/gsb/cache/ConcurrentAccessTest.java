package com.example.gsb.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Stress test: many threads performing mixed gets / puts / invalidates plus
 * periodic cleanUp, for both LRU and LFU, with a listener that randomly
 * throws. No exception may escape and capacity / stats invariants must hold.
 */
class ConcurrentAccessTest {

    private static final int THREADS = 8;
    private static final int OPS_PER_THREAD = 20_000;
    private static final int CAPACITY = 64;
    private static final int KEY_SPACE = 256;

    private ExecutorService pool;

    @AfterEach
    void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest(name = "concurrent mixed reads/writes with {0}")
    @EnumSource(EvictionStrategy.class)
    void mixedReadWriteRemainsConsistent(EvictionStrategy strategy) throws Exception {
        AtomicLong listenerFailures = new AtomicLong();
        AtomicLong getAttempts = new AtomicLong();

        RemovalListener<Integer, String> noisyListener = notification -> {
            if (ThreadLocalRandom.current().nextInt(10) == 0) {
                listenerFailures.incrementAndGet();
                throw new RuntimeException("simulated cleanup failure");
            }
        };

        LocalCache<Integer, String> cache = LocalCache.<Integer, String>newBuilder()
                .maximumSize(CAPACITY)
                .evictionStrategy(strategy)
                .expireAfterWrite(Duration.ofSeconds(30))
                .removalListener(noisyListener)
                .build();

        pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            final int workerId = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < OPS_PER_THREAD; i++) {
                    int key = ThreadLocalRandom.current().nextInt(KEY_SPACE);
                    int action = ThreadLocalRandom.current().nextInt(10);
                    if (action < 7) {
                        getAttempts.incrementAndGet();
                        cache.get(key); // result may be null, but must never throw
                    } else if (action < 9) {
                        cache.put(key, workerId + ":" + i);
                    } else {
                        cache.invalidate(key);
                    }
                    if (i % 500 == 0) {
                        cache.cleanUp();
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(2, TimeUnit.MINUTES); // surfaces any worker exception
        }

        // No thread died; the noisy listener was definitely exercised
        assertThat(listenerFailures.get()).isPositive();

        // Capacity invariant
        assertThat(cache.size()).isLessThanOrEqualTo(CAPACITY);

        // Every single get is accounted for as exactly one hit or miss, and
        // the snapshot counters agree (no lost updates / data corruption)
        StatsSnapshot stats = cache.statsSnapshot();
        assertThat(stats.requestCount()).isEqualTo(getAttempts.get());
        assertThat(stats.hitCount() + stats.missCount()).isEqualTo(getAttempts.get());
        assertThat(stats.evictionCount()).isNotNegative();
        assertThat(stats.expirationCount()).isNotNegative();

        // The cache remains fully functional after the storm
        cache.invalidateAll();
        cache.put("after".hashCode(), "survived");
        assertThat(cache.get("after".hashCode())).isEqualTo("survived");
    }

    @ParameterizedTest(name = "loader get with {0} never returns null or throws")
    @EnumSource(EvictionStrategy.class)
    void concurrentLoaderGets(EvictionStrategy strategy) throws Exception {
        LocalCache<Integer, Integer> cache = LocalCache.<Integer, Integer>newBuilder()
                .maximumSize(CAPACITY)
                .evictionStrategy(strategy)
                .build();

        pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 5_000; i++) {
                    int key = ThreadLocalRandom.current().nextInt(KEY_SPACE);
                    Integer value = cache.get(key, k -> k * 2);
                    assertThat(value).isEqualTo(key * 2);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(1, TimeUnit.MINUTES);
        }
        assertThat(cache.size()).isLessThanOrEqualTo(CAPACITY);
    }
}

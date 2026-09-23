package com.example.gsb.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CacheStatsTest {

    @Test
    void tracksHitsMissesAndEvictions() {
        Cache<Integer, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(2)
                .evictionPolicy(EvictionPolicy.LRU)
                .build());

        cache.put(1, "a");
        cache.put(2, "b");

        cache.get(1);   // hit
        cache.get(2);   // hit
        cache.get(3);   // miss

        cache.put(3, "c"); // evicts LRU key 1
        cache.get(1);      // miss (evicted)
        cache.get(3);      // hit

        CacheStats stats = cache.statsSnapshot();
        assertThat(stats.hits()).isEqualTo(3);
        assertThat(stats.misses()).isEqualTo(2);
        assertThat(stats.evictions()).isEqualTo(1);
        assertThat(stats.expiredEvictions()).isZero();
        assertThat(stats.requests()).isEqualTo(5);
        assertThat(stats.hitRate()).isCloseTo(0.6, within(0.0001));
        assertThat(stats.currentSize()).isEqualTo(2);
    }

    @Test
    void expiredRemovalCountedSeparatelyFromCapacityEviction() {
        FakeTicker ticker = new FakeTicker();
        Cache<Integer, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(2)
                .expireAfterWrite(Duration.ofSeconds(10))
                .ticker(ticker)
                .build());

        cache.put(1, "a");
        cache.put(2, "b");
        ticker.advance(Duration.ofSeconds(20));
        cache.get(1); // miss caused by expiry (lazy removal of key 1)
        cache.get(2); // miss caused by expiry (lazy removal of key 2)
        cache.put(3, "c");

        CacheStats stats = cache.statsSnapshot();
        assertThat(stats.expiredEvictions()).isEqualTo(2);
        assertThat(stats.evictions()).isZero();
        assertThat(stats.misses()).isEqualTo(2);
        assertThat(stats.currentSize()).isEqualTo(1);
    }

    @Test
    void emptyCacheReportsZeroStats() {
        Cache<Integer, String> cache = LocalCache.create(CacheConfig.newBuilder().build());
        CacheStats stats = cache.statsSnapshot();
        assertThat(stats.hits()).isZero();
        assertThat(stats.misses()).isZero();
        assertThat(stats.evictions()).isZero();
        assertThat(stats.expiredEvictions()).isZero();
        assertThat(stats.hitRate()).isZero();
    }
}

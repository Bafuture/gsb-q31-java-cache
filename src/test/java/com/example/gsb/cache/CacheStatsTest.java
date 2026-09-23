package com.example.gsb.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Counters and the immutable snapshot contract. */
class CacheStatsTest {

    @Test
    void countersTrackHitsMissesEvictionsAndExpirations() {
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(2)
                .build();

        cache.put("a", "1");
        cache.put("b", "2");

        assertThat(cache.get("a")).isEqualTo("1");  // hit
        assertThat(cache.get("a")).isEqualTo("1");  // hit
        assertThat(cache.get("missing")).isNull();  // miss
        assertThat(cache.get("other-missing")).isNull(); // miss

        cache.put("c", "3"); // evicts LRU = "b"

        assertThat(cache.get("b")).isNull();         // evicted -> miss
        assertThat(cache.get("a")).isEqualTo("1");

        StatsSnapshot snapshot = cache.statsSnapshot();
        assertThat(snapshot.hitCount()).isEqualTo(3);
        assertThat(snapshot.missCount()).isEqualTo(3);
        assertThat(snapshot.evictionCount()).isEqualTo(1);
        assertThat(snapshot.expirationCount()).isZero();
        assertThat(snapshot.requestCount()).isEqualTo(6);
        assertThat(snapshot.hitRate()).isEqualTo(0.5);
    }

    @Test
    void expiredReadCountsAsMissAndExpiration() {
        ManualTicker ticker = new ManualTicker();
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(10)
                .expireAfterWrite(java.time.Duration.ofMillis(10))
                .ticker(ticker)
                .build();

        cache.put("k", "v");
        ticker.advanceMillis(20);
        assertThat(cache.get("k")).isNull();

        StatsSnapshot snapshot = cache.statsSnapshot();
        assertThat(snapshot.expirationCount()).isEqualTo(1);
        assertThat(snapshot.missCount()).isEqualTo(1);
        assertThat(snapshot.hitCount()).isZero();
    }

    @Test
    void snapshotsAreIndependentPointInTimeCopies() {
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(10)
                .build();
        cache.put("k", "v");
        StatsSnapshot first = cache.statsSnapshot();
        cache.get("k");
        StatsSnapshot second = cache.statsSnapshot();

        assertThat(first.hitCount()).isZero();
        assertThat(second.hitCount()).isEqualTo(1);
    }
}

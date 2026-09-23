package com.example.gsb.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** TTL (expire-after-write) and TTI (expire-after-access) semantics. */
class ExpirationTest {

    @Test
    void ttlExpiresAfterFixedLifetimeRegardlessOfReads() {
        ManualTicker ticker = new ManualTicker();
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofMillis(100))
                .ticker(ticker)
                .build();

        cache.put("k", "v");
        ticker.advanceMillis(60);
        assertThat(cache.get("k")).isEqualTo("v"); // reads do not extend TTL
        ticker.advanceMillis(60);                  // 120ms after write
        assertThat(cache.get("k")).isNull();
        assertThat(cache.containsKey("k")).isFalse();
        assertThat(cache.statsSnapshot().expirationCount()).isEqualTo(1);
    }

    @Test
    void ttiExpiresOnlyAfterIdlePeriod() {
        ManualTicker ticker = new ManualTicker();
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(10)
                .expireAfterAccess(Duration.ofMillis(100))
                .ticker(ticker)
                .build();

        cache.put("k", "v");
        ticker.advanceMillis(60);
        assertThat(cache.get("k")).isEqualTo("v"); // access slides the window
        ticker.advanceMillis(60);                  // only 60ms since last access
        assertThat(cache.get("k")).isEqualTo("v");
        ticker.advanceMillis(100);                 // 100ms idle
        assertThat(cache.get("k")).isNull();
        assertThat(cache.statsSnapshot().expirationCount()).isEqualTo(1);
    }

    @Test
    void ttlAndTtiApplyTogetherWhicheverComesFirst() {
        ManualTicker ticker = new ManualTicker();
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofMillis(100))
                .expireAfterAccess(Duration.ofMillis(40))
                .ticker(ticker)
                .build();

        cache.put("k", "v");
        ticker.advanceMillis(30);
        assertThat(cache.get("k")).isEqualTo("v"); // slide TTI
        ticker.advanceMillis(30);
        assertThat(cache.get("k")).isEqualTo("v"); // still within both windows
        ticker.advanceMillis(30);                  // 90ms since write, 30ms since access...
        ticker.advanceMillis(20);                  // 110ms since write: TTL fires
        assertThat(cache.get("k")).isNull();
    }

    @Test
    void replacementRestartsTtlWindow() {
        ManualTicker ticker = new ManualTicker();
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(10)
                .expireAfterWrite(Duration.ofMillis(100))
                .ticker(ticker)
                .build();

        cache.put("k", "v1");
        ticker.advanceMillis(80);
        cache.put("k", "v2"); // fresh write window
        ticker.advanceMillis(80);
        assertThat(cache.get("k")).isEqualTo("v2");
        ticker.advanceMillis(30);
        assertThat(cache.get("k")).isNull();
    }

    @Test
    void expiredEntriesArePurgedBeforeEvictionAndNeverReturned() {
        ManualTicker ticker = new ManualTicker();
        RecordingListener<String, String> listener = new RecordingListener<>();
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(2)
                .expireAfterWrite(Duration.ofMillis(100))
                .ticker(ticker)
                .removalListener(listener)
                .build();

        cache.put("a", "1");
        cache.put("b", "2");
        ticker.advanceMillis(150); // both expired but still physically present

        // inserting at capacity must purge the two expired entries instead of
        // evicting them as SIZE, so "c" and "d" both fit without evictions
        cache.put("c", "3");
        assertThat(cache.statsSnapshot().evictionCount()).isZero();
        assertThat(cache.statsSnapshot().expirationCount()).isEqualTo(2);

        cache.put("d", "4");                       // both fit: no SIZE eviction yet
        cache.put("e", "5");                       // genuinely full: coldest "c" is evicted
        assertThat(cache.statsSnapshot().evictionCount()).isEqualTo(1);
        assertThat(cache.get("c")).isNull();
        assertThat(cache.get("d")).isEqualTo("4");
        assertThat(cache.get("e")).isEqualTo("5");
        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isNull();
        assertThat(listener.count(RemovalCause.EXPIRED)).isEqualTo(2);
        assertThat(listener.count(RemovalCause.SIZE)).isEqualTo(1);
    }

    @Test
    void cleanUpPurgesAllExpiredEntries() {
        ManualTicker ticker = new ManualTicker();
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(10)
                .expireAfterAccess(Duration.ofMillis(100))
                .ticker(ticker)
                .build();

        cache.put("a", "1");
        cache.put("b", "2");
        ticker.advanceMillis(120);
        // no read has triggered lazy expiry yet
        assertThat(cache.cleanUp()).isEqualTo(2);
        assertThat(cache.size()).isZero();
        assertThat(cache.cleanUp()).isZero();
    }
}

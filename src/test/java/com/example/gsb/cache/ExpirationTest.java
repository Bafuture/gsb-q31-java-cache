package com.example.gsb.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ExpirationTest {

    @Test
    void ttlExpiresRegardlessOfReads() {
        FakeTicker ticker = new FakeTicker();
        Cache<String, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(10)
                .expireAfterWrite(Duration.ofSeconds(10))
                .ticker(ticker)
                .build());

        cache.put("k", "v");
        ticker.advance(Duration.ofSeconds(5));
        assertThat(cache.get("k")).contains("v"); // reads do not extend TTL

        ticker.advance(Duration.ofSeconds(5));
        assertThat(cache.get("k")).isEmpty();      // exactly at 10s it is expired
        assertThat(cache.size()).isZero();
    }

    @Test
    void ttiExpiresAfterIdleAccessWindow() {
        FakeTicker ticker = new FakeTicker();
        Cache<String, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(10)
                .expireAfterAccess(Duration.ofSeconds(10))
                .ticker(ticker)
                .build());

        cache.put("k", "v");
        ticker.advance(Duration.ofSeconds(6));
        assertThat(cache.get("k")).contains("v"); // read refreshes the idle timer
        ticker.advance(Duration.ofSeconds(6));
        assertThat(cache.get("k")).contains("v"); // still within fresh window
        ticker.advance(Duration.ofSeconds(10));
        assertThat(cache.get("k")).isEmpty();     // 10s of no access -> gone
    }

    @Test
    void expiredEntryNeverReturnedAndCountsAsMissAndExpiry() {
        FakeTicker ticker = new FakeTicker();
        Cache<String, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(10)
                .expireAfterWrite(Duration.ofSeconds(1))
                .ticker(ticker)
                .build());

        cache.put("k", "v");
        ticker.advance(Duration.ofSeconds(2));

        assertThat(cache.get("k")).isEmpty();
        CacheStats stats = cache.statsSnapshot();
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.expiredEvictions()).isEqualTo(1);
        assertThat(stats.evictions()).isZero();
    }

    @Test
    void expiredEntriesArePurgedOnPutAndFreeCapacityWithoutEviction() {
        FakeTicker ticker = new FakeTicker();
        Cache<Integer, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(2)
                .expireAfterWrite(Duration.ofSeconds(10))
                .ticker(ticker)
                .build());

        cache.put(1, "a");
        cache.put(2, "b");
        ticker.advance(Duration.ofSeconds(11));
        cache.put(3, "c"); // 1 and 2 are purged as expired, no capacity eviction

        assertThat(cache.size()).isEqualTo(1);
        CacheStats stats = cache.statsSnapshot();
        assertThat(stats.evictions()).isZero();
        assertThat(stats.expiredEvictions()).isEqualTo(2);
    }

    @Test
    void cleanUpRemovesAllExpiredEntries() {
        FakeTicker ticker = new FakeTicker();
        Cache<Integer, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(5)
                .expireAfterAccess(Duration.ofSeconds(10))
                .ticker(ticker)
                .build());

        cache.put(1, "a");
        cache.put(2, "b");
        ticker.advance(Duration.ofSeconds(5));
        cache.get(1);                 // key 1 idle timer reset
        ticker.advance(Duration.ofSeconds(6));
        cache.cleanUp();

        assertThat(cache.get(1)).contains("a");
        assertThat(cache.get(2)).isEmpty();
    }

    @Test
    void perEntryDurationsOverrideDefaults() {
        FakeTicker ticker = new FakeTicker();
        Cache<String, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(10)
                .expireAfterWrite(Duration.ofSeconds(100))
                .ticker(ticker)
                .build());

        cache.put("short", "s", Duration.ofSeconds(1), null);
        cache.put("long", "l");
        ticker.advance(Duration.ofSeconds(2));

        assertThat(cache.get("short")).isEmpty();
        assertThat(cache.get("long")).contains("l");
    }

    @Test
    void ttlAndTtiCombinedExpireAtWhicheverComesFirst() {
        FakeTicker ticker = new FakeTicker();
        Cache<String, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(10)
                .expireAfterWrite(Duration.ofSeconds(100))
                .expireAfterAccess(Duration.ofSeconds(10))
                .ticker(ticker)
                .build());

        cache.put("k", "v");
        // Reads every 8s keep the 10s TTI window open, so TTI never fires.
        for (int i = 0; i < 12; i++) {
            ticker.advance(Duration.ofSeconds(8));
            assertThat(cache.get("k")).as("alive at %ds", 8 * (i + 1)).contains("v");
        }
        // Elapsed = 96s < 100s TTL. One more refresh still cannot beat TTL:
        ticker.advance(Duration.ofSeconds(8)); // 104s > TTL, idle gap only 8s
        assertThat(cache.get("k")).isEmpty();
    }
}

package com.example.gsb.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies LRU eviction order and replacement semantics. */
class LruEvictionTest {

    private LocalCache<String, String> cache() {
        return LocalCache.<String, String>newBuilder()
                .maximumSize(3)
                .evictionStrategy(EvictionStrategy.LRU)
                .build();
    }

    @Test
    void evictsLeastRecentlyUsedFirst() {
        LocalCache<String, String> cache = cache();
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        // touch "a", making "b" the coldest
        assertThat(cache.get("a")).isEqualTo("1");

        cache.put("d", "4");

        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.get("d")).isEqualTo("4");
        StatsSnapshot stats = cache.statsSnapshot();
        assertThat(stats.evictionCount()).isEqualTo(1);
        assertThat(stats.expirationCount()).isZero();
    }

    @Test
    void replacementRefreshesRecencyAndDoesNotGrowSize() {
        LocalCache<String, String> cache = cache();
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        cache.put("a", "11"); // replace: "a" becomes most recently used

        cache.put("d", "4"); // coldest is "b"

        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("11");
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.get("d")).isEqualTo("4");
        StatsSnapshot stats = cache.statsSnapshot();
        assertThat(stats.evictionCount()).isEqualTo(1);
    }

    @Test
    void overCapacityKeysNeverEvict() {
        LocalCache<String, String> cache = cache();
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        // replacing an existing key at capacity must not trigger eviction
        cache.put("b", "22");

        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.statsSnapshot().evictionCount()).isZero();
        assertThat(cache.get("b")).isEqualTo("22");
    }

    @Test
    void sequentialInsertionEvictsInInsertionOrder() {
        LocalCache<String, String> cache = cache();
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        cache.put("d", "4");
        cache.put("e", "5");

        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.get("d")).isEqualTo("4");
        assertThat(cache.get("e")).isEqualTo("5");
        assertThat(cache.statsSnapshot().evictionCount()).isEqualTo(2);
    }
}

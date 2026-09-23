package com.example.gsb.cache;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LruEvictionTest {

    private Cache<Integer, String> newCache(int capacity) {
        return LocalCache.create(CacheConfig.newBuilder()
                .capacity(capacity)
                .evictionPolicy(EvictionPolicy.LRU)
                .build());
    }

    @Test
    void evictsLeastRecentlyUsedFirst() {
        Cache<Integer, String> cache = newCache(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");

        cache.put(4, "d"); // capacity reached: key 1 is the LRU entry

        assertThat(cache.get(1)).isEmpty();
        assertThat(cache.get(2)).contains("b");
        assertThat(cache.get(3)).contains("c");
        assertThat(cache.get(4)).contains("d");
        assertThat(cache.size()).isEqualTo(3);
    }

    @Test
    void readRefreshesRecency() {
        Cache<Integer, String> cache = newCache(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");

        assertThat(cache.get(1)).contains("a"); // 1 is now most-recent
        cache.put(4, "d");                      // 2 becomes the victim

        assertThat(cache.get(1)).contains("a");
        assertThat(cache.get(2)).isEmpty();
        assertThat(cache.get(3)).contains("c");
        assertThat(cache.get(4)).contains("d");
    }

    @Test
    void replacingExistingKeyDoesNotGrowOrEvict() {
        Cache<Integer, String> cache = newCache(2);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(1, "a2");

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get(1)).contains("a2");
        assertThat(cache.get(2)).contains("b");
        assertThat(cache.statsSnapshot().evictions()).isZero();
    }

    @Test
    void invalidateRemovesEntryAndFreesCapacity() {
        Cache<Integer, String> cache = newCache(2);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.invalidate(1);
        cache.put(3, "c");

        assertThat(cache.get(1)).isEqualTo(Optional.empty());
        assertThat(cache.get(2)).contains("b");
        assertThat(cache.get(3)).contains("c");
    }
}

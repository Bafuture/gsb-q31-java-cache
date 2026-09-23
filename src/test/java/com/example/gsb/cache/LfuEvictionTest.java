package com.example.gsb.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LfuEvictionTest {

    private Cache<Integer, String> newCache(int capacity) {
        return LocalCache.create(CacheConfig.newBuilder()
                .capacity(capacity)
                .evictionPolicy(EvictionPolicy.LFU)
                .build());
    }

    @Test
    void evictsLeastFrequentlyUsedFirst() {
        Cache<Integer, String> cache = newCache(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");

        cache.get(2); // frequencies: 2 -> 2
        cache.get(2); // 2 -> 3
        cache.get(3); // 3 -> 2

        cache.put(4, "d"); // key 1 (frequency 1) must be evicted

        assertThat(cache.get(1)).isEmpty();
        assertThat(cache.get(2)).contains("b");
        assertThat(cache.get(3)).contains("c");
        assertThat(cache.get(4)).contains("d");
    }

    @Test
    void tiesBreakByRecency() {
        Cache<Integer, String> cache = newCache(3);
        cache.put(1, "a");
        cache.put(2, "b");
        cache.put(3, "c");
        // No reads: every key has frequency 1; key 1 is least-recent in bucket 1.

        cache.put(4, "d");

        assertThat(cache.get(1)).isEmpty();
        assertThat(cache.get(2)).contains("b");
        assertThat(cache.get(3)).contains("c");
        assertThat(cache.get(4)).contains("d");
    }

    @Test
    void frequentEntrySurvivesManyInserts() {
        Cache<Integer, String> cache = newCache(4);
        cache.put(0, "hot");
        for (int i = 0; i < 5; i++) {
            assertThat(cache.get(0)).contains("hot");
        }

        cache.put(1, "1");
        cache.put(2, "2");
        cache.put(3, "3");
        cache.put(4, "4"); // evicts one of the cold keys, never 0

        assertThat(cache.get(0)).contains("hot");
        assertThat(cache.size()).isEqualTo(4);
    }

    @Test
    void replacementResetsFrequency() {
        Cache<Integer, String> cache = newCache(2);
        cache.put(1, "a");
        cache.get(1);
        cache.get(1); // frequency 3
        cache.put(2, "b");

        cache.put(1, "a2"); // replacement restarts frequency at 1
        cache.get(2);       // key 2 climbs to frequency 2; key 1 stays at 1
        cache.put(3, "c");  // key 1 is now the LFU entry

        assertThat(cache.get(1)).isEmpty();
        assertThat(cache.get(2)).contains("b");
        assertThat(cache.get(3)).contains("c");
    }
}

package com.example.gsb.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Verifies LFU frequency ordering and the LRU tie-break inside a bucket. */
class LfuEvictionTest {

    private LocalCache<String, String> cache() {
        return LocalCache.<String, String>newBuilder()
                .maximumSize(3)
                .evictionStrategy(EvictionStrategy.LFU)
                .build();
    }

    @Test
    void evictsLeastFrequentFirst() {
        LocalCache<String, String> cache = cache();
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        // frequencies: a=3, b=2, c=1
        cache.get("b");
        cache.get("a");
        cache.get("a");

        cache.put("d", "4"); // c (freq 1) must be evicted, not b even though b is LRU

        assertThat(cache.get("c")).isNull();
        assertThat(cache.get("a")).isEqualTo("1");
        assertThat(cache.get("b")).isEqualTo("2");
        assertThat(cache.get("d")).isEqualTo("4");
        assertThat(cache.statsSnapshot().evictionCount()).isEqualTo(1);
    }

    @Test
    void tiesWithinFrequencyBucketBreakByRecency() {
        LocalCache<String, String> cache = cache();
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3"); // all entries sit in the frequency-1 bucket, ordered a < b < c

        cache.put("d", "4"); // oldest-in-bucket "a" is evicted
        cache.put("e", "5"); // then "b"

        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.get("d")).isEqualTo("4");
        assertThat(cache.get("e")).isEqualTo("5");
        assertThat(cache.statsSnapshot().evictionCount()).isEqualTo(2);
    }

    @Test
    void replacementResetsFrequency() {
        LocalCache<String, String> cache = cache();
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        cache.get("a");
        cache.get("a"); // a now freq 3, the clear hot key

        cache.put("a", "11"); // replacement restarts frequency at 1

        cache.put("d", "4"); // coldest freq-1 node: b (b earlier than a, a earlier than c)

        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("11");
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.get("d")).isEqualTo("4");
        assertThat(cache.statsSnapshot().evictionCount()).isEqualTo(1);
    }
}

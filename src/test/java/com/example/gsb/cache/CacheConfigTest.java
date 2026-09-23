package com.example.gsb.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheConfigTest {

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> CacheConfig.newBuilder().capacity(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CacheConfig.newBuilder().capacity(-5).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroExpiryDurations() {
        assertThatThrownBy(() -> CacheConfig.newBuilder().expireAfterWrite(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        Cache<Integer, Integer> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(1)
                .build());
        assertThatThrownBy(() -> cache.put(1, 1, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

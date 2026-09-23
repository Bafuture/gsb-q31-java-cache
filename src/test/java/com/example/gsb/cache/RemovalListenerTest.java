package com.example.gsb.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Removal notifications, causes and isolation of listener failures. */
class RemovalListenerTest {

    @Test
    void notifiesForEveryRemovalCause() {
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
        cache.put("a", "11");         // REPLACED
        cache.put("c", "3");          // evicts b (a was refreshed) -> SIZE
        cache.invalidate("c");        // EXPLICIT
        ticker.advanceMillis(150);
        cache.get("a");               // lazy expiry -> EXPIRED

        assertThat(listener.count(RemovalCause.REPLACED)).isEqualTo(1);
        assertThat(listener.count(RemovalCause.SIZE)).isEqualTo(1);
        assertThat(listener.count(RemovalCause.EXPLICIT)).isEqualTo(1);
        assertThat(listener.count(RemovalCause.EXPIRED)).isEqualTo(1);

        RemovalNotification<String, String> replacement = listener.notifications().stream()
                .filter(n -> n.getCause() == RemovalCause.REPLACED)
                .findFirst().orElseThrow();
        assertThat(replacement.getKey()).isEqualTo("a");
        assertThat(replacement.getValue()).isEqualTo("1");
    }

    @Test
    void listenerThrowingDoesNotBreakCacheOperations() {
        AtomicInteger delivered = new AtomicInteger();
        RemovalListener<String, String> failingListener = notification -> {
            delivered.incrementAndGet();
            throw new IllegalStateException("boom from resource cleanup");
        };

        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(2)
                .removalListener(failingListener)
                .build();

        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3"); // evicts "a" -> listener throws, must be swallowed
        cache.put("d", "4"); // and again, proving the cache keeps working

        assertThat(delivered).hasValue(2);
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.get("d")).isEqualTo("4");
        assertThat(cache.get("a")).isNull();
        assertThat(cache.statsSnapshot().evictionCount()).isEqualTo(2);
    }

    @Test
    void listenerErrorIsAlsoIsolatedOnExpiry() {
        ManualTicker ticker = new ManualTicker();
        RemovalListener<String, String> failingListener = notification -> {
            throw new RuntimeException("cleanup failed");
        };
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(2)
                .expireAfterWrite(Duration.ofMillis(10))
                .ticker(ticker)
                .removalListener(failingListener)
                .build();

        cache.put("a", "1");
        ticker.advanceMillis(20);

        assertThat(cache.get("a")).isNull();      // expiry notification throws internally
        cache.put("b", "2");                       // cache still usable
        assertThat(cache.get("b")).isEqualTo("2");
    }

    @Test
    void invalidateAllNotifiesEveryEntry() {
        RecordingListener<String, String> listener = new RecordingListener<>();
        LocalCache<String, String> cache = LocalCache.<String, String>newBuilder()
                .maximumSize(10)
                .removalListener(listener)
                .build();

        cache.put("a", "1");
        cache.put("b", "2");
        cache.invalidateAll();

        assertThat(listener.count(RemovalCause.EXPLICIT)).isEqualTo(2);
        assertThat(cache.size()).isZero();
    }
}

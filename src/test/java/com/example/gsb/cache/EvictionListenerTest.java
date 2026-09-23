package com.example.gsb.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class EvictionListenerTest {

    private record Event(String key, String value, RemovalCause cause) {
    }

    @Test
    void notifiesForEveryRemovalCause() {
        List<Event> events = new CopyOnWriteArrayList<>();
        FakeTicker ticker = new FakeTicker();
        EvictionListener<String, String> listener = (k, v, cause) -> events.add(new Event(k, v, cause));
        Cache<String, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(2)
                .evictionPolicy(EvictionPolicy.LRU)
                .expireAfterWrite(Duration.ofSeconds(10))
                .evictionListener(listener)
                .ticker(ticker)
                .build());

        cache.put("a", "1");
        cache.put("a", "2");                          // REPLACED
        cache.invalidate("a");                        // REMOVED
        cache.put("b", "3");
        cache.put("c", "4");
        ticker.advance(Duration.ofSeconds(11));
        cache.put("d", "5");                          // b,c EXPIRED; d fits free slot
        cache.put("e", "6");
        cache.put("f", "7");                          // capacity eviction (d or e)

        assertThat(events).extracting(Event::cause)
                .contains(RemovalCause.REPLACED, RemovalCause.REMOVED,
                        RemovalCause.EXPIRED, RemovalCause.EVICTED);
        assertThat(events).filteredOn(e -> e.cause() == RemovalCause.REPLACED)
                .singleElement()
                .extracting(Event::value).isEqualTo("1");
    }

    @Test
    void listenerThrowingDoesNotBreakCache() {
        Cache<Integer, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(2)
                .evictionListener(new EvictionListener<Integer, String>() {
                    private boolean alternate;

                    @Override
                    public void onRemoval(Integer key, String value, RemovalCause cause) {
                        alternate = !alternate;
                        if (alternate) {
                            throw new IllegalStateException("boom: " + key);
                        }
                        throw new RuntimeException("also boom: " + key);
                    }
                })
                .build());

        for (int i = 0; i < 200; i++) {
            cache.put(i, "v" + i);
            assertThat(cache.get(i)).isPresent();
            cache.invalidate(i);
            assertThat(cache.get(i)).isEmpty();
        }
        assertThat(cache.size()).isZero();
        CacheStats stats = cache.statsSnapshot();
        assertThat(stats.requests()).isEqualTo(400L);
    }

    @Test
    void listenerSeesCorrectKeyAndValueOnEviction() {
        List<Event> events = new CopyOnWriteArrayList<>();
        EvictionListener<Integer, String> listener =
                (k, v, cause) -> events.add(new Event(String.valueOf(k), v, cause));
        Cache<Integer, String> cache = LocalCache.create(CacheConfig.newBuilder()
                .capacity(1)
                .evictionPolicy(EvictionPolicy.LFU)
                .evictionListener(listener)
                .build());

        cache.put(1, "one");
        cache.put(2, "two"); // evicts key 1

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.key()).isEqualTo("1");
            assertThat(event.value()).isEqualTo("one");
            assertThat(event.cause()).isEqualTo(RemovalCause.EVICTED);
        });
    }
}

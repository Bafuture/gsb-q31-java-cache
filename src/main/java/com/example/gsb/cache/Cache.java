package com.example.gsb.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * In-process key/value cache with capacity bounds, eviction and expiry.
 *
 * <p>All methods are thread-safe.
 */
public interface Cache<K, V> {

    /** Returns the value associated with the key, or empty on miss/expiry. */
    Optional<V> get(K key);

    /** Stores a value using the cache's default TTL/TTI settings. */
    void put(K key, V value);

    /**
     * Stores a value with per-entry TTL/TTI. A {@code null} duration means
     * "use the cache default"; {@link Duration#ZERO} is rejected.
     */
    void put(K key, V value, Duration ttl, Duration tti);

    /** Removes one entry; no-op if the key is absent. */
    void invalidate(K key);

    /** Removes every entry. Listeners are notified with cause REMOVED. */
    void invalidateAll();

    /**
     * Proactively scans out expired entries. Expiry is otherwise applied
     * lazily on access and when room is needed for a new entry.
     */
    void cleanUp();

    int size();

    CacheStats statsSnapshot();
}

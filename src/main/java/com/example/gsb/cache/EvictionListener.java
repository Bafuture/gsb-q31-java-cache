package com.example.gsb.cache;

/**
 * Callback invoked after an entry leaves the cache.
 *
 * <p>Listeners must not throw: any exception is caught and swallowed by the
 * cache so that a faulty listener cannot break cache operations. Callbacks
 * are invoked <b>outside</b> the cache's internal lock.
 */
@FunctionalInterface
public interface EvictionListener<K, V> {

    void onRemoval(K key, V value, RemovalCause cause);
}

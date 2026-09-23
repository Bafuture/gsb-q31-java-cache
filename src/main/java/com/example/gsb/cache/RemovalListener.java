package com.example.gsb.cache;

/**
 * Callback invoked after an entry is removed from the cache.
 *
 * <p>Notifications are delivered outside the cache lock, so implementations
 * are allowed to call back into the cache. Any exception thrown by a listener
 * is swallowed and never propagates to the read/write operation that triggered
 * the removal.
 */
@FunctionalInterface
public interface RemovalListener<K, V> {

    void onRemoval(RemovalNotification<K, V> notification);
}

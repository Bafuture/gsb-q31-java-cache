package com.example.gsb.cache;

/**
 * Strategy-specific index over live entries.
 *
 * <p>Every method is invoked while holding the cache lock.
 */
interface EvictionTracker<K, V> {

    /** Registers a freshly inserted entry (initial frequency is 1). */
    void add(CacheEntry<K, V> entry);

    /** Records a read hit or a value replacement on an existing entry. */
    void recordAccess(CacheEntry<K, V> entry, long nowNanos);

    /** Removes an entry from the index (eviction, expiry, explicit invalidation). */
    void remove(CacheEntry<K, V> entry);

    /** Returns and unlinks the entry selected for eviction, or {@code null} if empty. */
    CacheEntry<K, V> evict();
}

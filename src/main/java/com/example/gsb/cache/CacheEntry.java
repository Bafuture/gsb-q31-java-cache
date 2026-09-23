package com.example.gsb.cache;

/**
 * Cache entry plus the intrusive linked-list pointers and bookkeeping
 * shared by the LRU and LFU eviction structures.
 *
 * <p>All fields are mutated only while holding the cache lock.
 */
final class CacheEntry<K, V> {

    final K key;
    volatile V value;

    /** {@link System#nanoTime()}-based timestamp of the last write. */
    long writeNanos;
    /** {@link System#nanoTime()}-based timestamp of the last read or write. */
    long accessNanos;
    /** Number of hits since insertion; used by LFU. */
    int frequency;

    /** Intrusive pointers for an {@link EntryDeque} (LRU list or one LFU bucket). */
    CacheEntry<K, V> prev;
    CacheEntry<K, V> next;

    CacheEntry(K key, V value, long nowNanos) {
        this.key = key;
        this.value = value;
        this.writeNanos = nowNanos;
        this.accessNanos = nowNanos;
        this.frequency = 1;
    }

    /** Whether this entry has outlived its TTL or TTI at {@code nowNanos}. */
    boolean isExpiredAt(long nowNanos, long ttlNanos, long ttiNanos) {
        if (ttlNanos > 0 && nowNanos - writeNanos >= ttlNanos) {
            return true;
        }
        return ttiNanos > 0 && nowNanos - accessNanos >= ttiNanos;
    }
}

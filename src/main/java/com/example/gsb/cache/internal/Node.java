package com.example.gsb.cache.internal;

/**
 * Doubly-linked entry node.
 *
 * <p>For LRU the links form the single recency list (most-recent at head).
 * For LFU the links belong to exactly one frequency-bucket list at a time;
 * nodes are relinked on every access. All access is performed under the
 * cache's lock.
 */
public final class Node<K, V> {

    public final K key;
    public V value;

    public long createdAtNanos;
    public long lastAccessNanos;
    public final long ttlNanos;   // -1 means no write expiry
    public final long ttiNanos;   // -1 means no access expiry

    public long frequency;

    public Node<K, V> prev;
    public Node<K, V> next;

    public Node(K key, V value,
                long nowNanos, long ttlNanos, long ttiNanos) {
        this.key = key;
        this.value = value;
        this.createdAtNanos = nowNanos;
        this.lastAccessNanos = nowNanos;
        this.ttlNanos = ttlNanos;
        this.ttiNanos = ttiNanos;
        this.frequency = 1;
    }

    public boolean isExpired(long nowNanos) {
        if (ttlNanos > 0 && nowNanos - createdAtNanos >= ttlNanos) {
            return true;
        }
        return ttiNanos > 0 && nowNanos - lastAccessNanos >= ttiNanos;
    }
}

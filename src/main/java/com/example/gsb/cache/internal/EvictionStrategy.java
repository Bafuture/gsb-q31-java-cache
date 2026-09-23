package com.example.gsb.cache.internal;

/**
 * Pluggable internal eviction bookkeeping.
 *
 * <p>Every method is called while holding the cache lock. Implementations may
 * assume the node is present in the structure (onAccess/onRemove) or absent
 * (onInsert).
 */
public interface EvictionStrategy<K, V> {

    /** Registers a freshly inserted node. */
    void onInsert(Node<K, V> node);

    /** Reorders bookkeeping after a successful read. */
    void onAccess(Node<K, V> node);

    /** Drops a node that leaves the cache for any reason. */
    void onRemove(Node<K, V> node);

    /** Returns the node that should be evicted next, or null if empty. */
    Node<K, V> evictionCandidate();
}

package com.example.gsb.cache;

/** Selects which surviving entry is removed once the cache reaches capacity. */
public enum EvictionPolicy {
    /** Least Recently Used: the entry whose last access is oldest is evicted. */
    LRU,
    /**
     * Least Frequently Used: the entry with the fewest accesses is evicted;
     * ties are broken by least-recently-used order.
     */
    LFU
}

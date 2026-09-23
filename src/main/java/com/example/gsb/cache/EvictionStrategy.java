package com.example.gsb.cache;

/**
 * Selects which entry is evicted once the cache exceeds its capacity.
 */
public enum EvictionStrategy {
    /** Least recently used: the entry with the oldest access time goes first. */
    LRU,
    /** Least frequently used: the entry with the smallest access count goes first (ties broken by recency). */
    LFU
}

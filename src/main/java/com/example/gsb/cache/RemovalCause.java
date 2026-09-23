package com.example.gsb.cache;

/**
 * Why an entry was removed from the cache.
 */
public enum RemovalCause {
    /** Evicted because the cache exceeded its capacity bound. */
    SIZE,
    /** Removed because its TTL or TTI had elapsed. */
    EXPIRED,
    /** Replaced by a new value for the same key. */
    REPLACED,
    /** Removed explicitly via {@code invalidate} / {@code invalidateAll}. */
    EXPLICIT
}

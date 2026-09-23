package com.example.gsb.cache;

/**
 * Immutable point-in-time copy of the cache counters.
 */
public record StatsSnapshot(long hitCount,
                            long missCount,
                            long evictionCount,
                            long expirationCount) {

    public long requestCount() {
        return hitCount + missCount;
    }

    public double hitRate() {
        long requests = requestCount();
        return requests == 0 ? 0.0 : (double) hitCount / requests;
    }
}

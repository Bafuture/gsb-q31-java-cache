package com.example.gsb.cache;

/**
 * Immutable point-in-time statistics snapshot.
 *
 * <p>{@code evictions} counts capacity-driven removals, {@code expiredEvictions}
 * counts TTL/TTI-driven removals. Both are counted as misses on the triggering
 * read when applicable.
 */
public record CacheStats(long hits,
                         long misses,
                         long evictions,
                         long expiredEvictions,
                         long currentSize) {

    public long requests() {
        return hits + misses;
    }

    public double hitRate() {
        long requests = requests();
        return requests == 0 ? 0.0 : (double) hits / requests;
    }
}

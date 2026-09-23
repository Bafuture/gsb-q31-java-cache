package com.example.gsb.cache;

/** Why an entry left the cache. */
public enum RemovalCause {
    /** Removed because the capacity bound was reached. */
    EVICTED,
    /** Removed because its TTL or TTI expired. */
    EXPIRED,
    /** Removed by an explicit {@code invalidate} or {@code clear} call. */
    REMOVED,
    /** Replaced by a new value written under the same key. */
    REPLACED
}

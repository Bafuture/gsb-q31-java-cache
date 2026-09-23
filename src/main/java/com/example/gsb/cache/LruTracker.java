package com.example.gsb.cache;

/**
 * LRU index backed by a single hand-written {@link EntryDeque}.
 *
 * <p>Every access moves the entry to the head, so the tail is always the
 * least recently used node.
 */
final class LruTracker<K, V> implements EvictionTracker<K, V> {

    private final EntryDeque<K, V> lru = new EntryDeque<>();

    @Override
    public void add(CacheEntry<K, V> entry) {
        lru.addFirst(entry);
    }

    @Override
    public void recordAccess(CacheEntry<K, V> entry, long nowNanos) {
        lru.remove(entry);
        lru.addFirst(entry);
    }

    @Override
    public void remove(CacheEntry<K, V> entry) {
        lru.remove(entry);
    }

    @Override
    public CacheEntry<K, V> evict() {
        CacheEntry<K, V> victim = lru.peekLast();
        if (victim != null) {
            lru.remove(victim);
        }
        return victim;
    }
}

package com.example.gsb.cache;

import java.util.HashMap;
import java.util.Map;

/**
 * LFU index: one intrusive {@link EntryDeque} per access-frequency value.
 *
 * <p>{@code minFrequency} always points at the lowest non-empty bucket, so
 * selecting a victim is O(1). Moving an entry on access is O(1) amortised:
 * unlink from bucket {@code f}, link at the head of bucket {@code f + 1},
 * and lazily advance {@code minFrequency} when its bucket drains. Within a
 * bucket the coldest (least recently used) node is evicted first, which
 * deterministically breaks frequency ties.
 *
 * <p>This is the classic "O(1) LFU" bucket organisation; the frequency
 * counters intentionally never decay (see known limitations in the README).
 */
final class LfuTracker<K, V> implements EvictionTracker<K, V> {

    private final Map<Integer, EntryDeque<K, V>> buckets = new HashMap<>();
    private int minFrequency = Integer.MAX_VALUE;

    @Override
    public void add(CacheEntry<K, V> entry) {
        // New (or replaced) entries start in the frequency-1 bucket.
        entry.frequency = 1;
        bucket(1).addFirst(entry);
        minFrequency = 1;
    }

    @Override
    public void recordAccess(CacheEntry<K, V> entry, long nowNanos) {
        int oldFrequency = entry.frequency;
        EntryDeque<K, V> oldBucket = buckets.get(oldFrequency);
        oldBucket.remove(entry);
        if (oldBucket.isEmpty() && oldFrequency == minFrequency) {
            advanceMinFrequency(oldFrequency);
        }
        entry.frequency = oldFrequency + 1;
        bucket(oldFrequency + 1).addFirst(entry);
    }

    @Override
    public void remove(CacheEntry<K, V> entry) {
        EntryDeque<K, V> current = buckets.get(entry.frequency);
        if (current == null) {
            return;
        }
        current.remove(entry);
        if (current.isEmpty() && entry.frequency == minFrequency) {
            advanceMinFrequency(entry.frequency);
        }
    }

    @Override
    public CacheEntry<K, V> evict() {
        EntryDeque<K, V> bucket = buckets.get(minFrequency);
        if (bucket == null || bucket.isEmpty()) {
            return null;
        }
        CacheEntry<K, V> victim = bucket.peekLast();
        bucket.remove(victim);
        if (bucket.isEmpty()) {
            advanceMinFrequency(minFrequency);
        }
        return victim;
    }

    private EntryDeque<K, V> bucket(int frequency) {
        return buckets.computeIfAbsent(frequency, ignored -> new EntryDeque<>());
    }

    private void advanceMinFrequency(int drainedFrequency) {
        // Frequency values are compact (1, 2, 3, ...), so scanning upwards from
        // the drained bucket finds the next non-empty one cheaply in practice.
        for (int frequency = drainedFrequency + 1; frequency < Integer.MAX_VALUE; frequency++) {
            EntryDeque<K, V> bucket = buckets.get(frequency);
            if (bucket != null && !bucket.isEmpty()) {
                minFrequency = frequency;
                return;
            }
        }
        minFrequency = Integer.MAX_VALUE;
    }
}

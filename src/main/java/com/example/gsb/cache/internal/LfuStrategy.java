package com.example.gsb.cache.internal;

import java.util.HashMap;
import java.util.Map;

/**
 * O(1) LFU implemented with frequency buckets.
 *
 * <p>Each frequency value owns a doubly-linked list of nodes. Nodes are added
 * at the bucket head on insertion/access, so within a bucket the tail is the
 * least-recently-used node. A pointer to the smallest non-empty frequency
 * ({@code minFrequency}) makes selecting the eviction victim constant time:
 * the tail of that bucket.
 */
public final class LfuStrategy<K, V> implements EvictionStrategy<K, V> {

    private static final class Bucket<K, V> {
        Node<K, V> head;
        Node<K, V> tail;
        int size;
    }

    private final Map<Long, Bucket<K, V>> buckets = new HashMap<>();
    private long minFrequency = 0L;
    private int totalSize = 0;

    @Override
    public void onInsert(Node<K, V> node) {
        node.frequency = 1L;
        addToBucket(1L, node);
        minFrequency = 1L;
        totalSize++;
    }

    @Override
    public void onAccess(Node<K, V> node) {
        long oldFreq = node.frequency;
        removeFromBucket(oldFreq, node);
        long newFreq = oldFreq + 1;
        node.frequency = newFreq;
        addToBucket(newFreq, node);

        if (oldFreq == minFrequency && buckets.get(oldFreq) == null) {
            minFrequency = newFreq;
        }
    }

    @Override
    public void onRemove(Node<K, V> node) {
        long freq = node.frequency;
        removeFromBucket(freq, node);
        totalSize--;
        if (totalSize == 0) {
            minFrequency = 0L;
        } else if (freq == minFrequency && buckets.get(freq) == null) {
            minFrequency = minBucketFrequency();
        }
    }

    @Override
    public Node<K, V> evictionCandidate() {
        Bucket<K, V> bucket = buckets.get(minFrequency);
        return bucket == null ? null : bucket.tail;
    }

    private void addToBucket(long freq, Node<K, V> node) {
        Bucket<K, V> bucket = buckets.computeIfAbsent(freq, f -> new Bucket<>());
        node.prev = null;
        node.next = bucket.head;
        if (bucket.head != null) {
            bucket.head.prev = node;
        }
        bucket.head = node;
        if (bucket.tail == null) {
            bucket.tail = node;
        }
        bucket.size++;
    }

    private void removeFromBucket(long freq, Node<K, V> node) {
        Bucket<K, V> bucket = buckets.get(freq);
        Node<K, V> p = node.prev;
        Node<K, V> n = node.next;
        if (p != null) {
            p.next = n;
        } else if (bucket.head == node) {
            bucket.head = n;
        }
        if (n != null) {
            n.prev = p;
        } else if (bucket.tail == node) {
            bucket.tail = p;
        }
        node.prev = null;
        node.next = null;
        bucket.size--;
        if (bucket.size == 0) {
            buckets.remove(freq);
        }
    }

    private long minBucketFrequency() {
        long min = Long.MAX_VALUE;
        for (long freq : buckets.keySet()) {
            if (freq < min) {
                min = freq;
            }
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }
}

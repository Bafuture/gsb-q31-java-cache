package com.example.gsb.cache;

import com.example.gsb.cache.internal.EvictionStrategy;
import com.example.gsb.cache.internal.LfuStrategy;
import com.example.gsb.cache.internal.LruStrategy;
import com.example.gsb.cache.internal.Node;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-local cache backed by a {@link HashMap} plus a pluggable,
 * hand-rolled eviction structure (LRU or LFU).
 *
 * <p>All reads and writes run under one {@link ReentrantLock}, which keeps the
 * map and the eviction bookkeeping mutually consistent. Removal notifications
 * are collected while holding the lock and fired only after releasing it, so a
 * slow or reentrant listener cannot block other threads or leak the lock.
 */
public final class LocalCache<K, V> implements Cache<K, V> {

    private static final long NO_EXPIRY = -1L;

    private final int capacity;
    private final long defaultTtlNanos;
    private final long defaultTtiNanos;
    private final Ticker ticker;
    private final EvictionListener<K, V> listener;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<K, Node<K, V>> entries;
    private final EvictionStrategy<K, V> strategy;

    private long hits;
    private long misses;
    private long evictions;
    private long expiredEvictions;

    public LocalCache(CacheConfig config) {
        this.capacity = config.capacity();
        this.defaultTtlNanos = toNanos(config.ttl());
        this.defaultTtiNanos = toNanos(config.tti());
        this.ticker = config.ticker();
        this.listener = config.listener();
        this.entries = new HashMap<>(Math.max(16, (int) (capacity / 0.75f) + 1));
        this.strategy = switch (config.evictionPolicy()) {
            case LRU -> new LruStrategy<>();
            case LFU -> new LfuStrategy<>();
        };
    }

    public static <K, V> Cache<K, V> create(CacheConfig config) {
        return new LocalCache<>(config);
    }

    @Override
    public Optional<V> get(K key) {
        List<Removal<K, V>> notifications = List.of();
        Optional<V> result;
        lock.lock();
        try {
            long now = ticker.readNanos();
            Node<K, V> node = entries.get(key);
            if (node == null) {
                misses++;
                return Optional.empty();
            }
            if (node.isExpired(now)) {
                notifications = new ArrayList<>(1);
                removeNode(node, RemovalCause.EXPIRED, notifications);
                expiredEvictions++;
                misses++;
                result = Optional.empty();
            } else {
                node.lastAccessNanos = now;
                strategy.onAccess(node);
                hits++;
                result = Optional.ofNullable(node.value);
            }
        } finally {
            lock.unlock();
        }
        fireNotifications(notifications);
        return result;
    }

    @Override
    public void put(K key, V value) {
        put(key, value, null, null);
    }

    @Override
    public void put(K key, V value, Duration ttl, Duration tti) {
        long ttlNanos = ttl == null ? defaultTtlNanos : requirePositive(ttl.toNanos(), "ttl");
        long ttiNanos = tti == null ? defaultTtiNanos : requirePositive(tti.toNanos(), "tti");

        List<Removal<K, V>> notifications;
        lock.lock();
        try {
            long now = ticker.readNanos();
            Node<K, V> existing = entries.get(key);
            notifications = new ArrayList<>();
            if (existing != null) {
                removeNode(existing, RemovalCause.REPLACED, notifications);
            } else {
                makeRoom(now, notifications);
            }
            Node<K, V> node = new Node<>(key, value, now, ttlNanos, ttiNanos);
            entries.put(key, node);
            strategy.onInsert(node);
        } finally {
            lock.unlock();
        }
        fireNotifications(notifications);
    }

    @Override
    public void invalidate(K key) {
        List<Removal<K, V>> notifications;
        lock.lock();
        try {
            Node<K, V> node = entries.remove(key);
            if (node != null) {
                strategy.onRemove(node);
                notifications = List.of(new Removal<>(node.key, node.value, RemovalCause.REMOVED));
            } else {
                notifications = List.of();
            }
        } finally {
            lock.unlock();
        }
        fireNotifications(notifications);
    }

    @Override
    public void invalidateAll() {
        List<Removal<K, V>> notifications;
        lock.lock();
        try {
            notifications = new ArrayList<>(entries.size());
            for (Node<K, V> node : entries.values()) {
                strategy.onRemove(node);
                notifications.add(new Removal<>(node.key, node.value, RemovalCause.REMOVED));
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
        fireNotifications(notifications);
    }

    @Override
    public void cleanUp() {
        List<Removal<K, V>> notifications;
        lock.lock();
        try {
            long now = ticker.readNanos();
            notifications = new ArrayList<>();
            var iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Node<K, V> node = iterator.next();
                if (node.isExpired(now)) {
                    iterator.remove();
                    strategy.onRemove(node);
                    expiredEvictions++;
                    notifications.add(new Removal<>(node.key, node.value, RemovalCause.EXPIRED));
                }
            }
        } finally {
            lock.unlock();
        }
        fireNotifications(notifications);
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats statsSnapshot() {
        lock.lock();
        try {
            return new CacheStats(hits, misses, evictions, expiredEvictions, entries.size());
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------
    // Lock-private helpers (the caller must hold {@link #lock})
    // ------------------------------------------------------------------

    private void makeRoom(long now, List<Removal<K, V>> notifications) {
        if (entries.size() < capacity) {
            return;
        }
        List<Node<K, V>> expired = new ArrayList<>();
        for (Node<K, V> node : entries.values()) {
            if (node.isExpired(now)) {
                expired.add(node);
            }
        }
        for (Node<K, V> node : expired) {
            removeNode(node, RemovalCause.EXPIRED, notifications);
            expiredEvictions++;
        }

        if (entries.size() < capacity) {
            return;
        }
        Node<K, V> victim = strategy.evictionCandidate();
        if (victim != null) {
            removeNode(victim, RemovalCause.EVICTED, notifications);
            evictions++;
        }
    }

    private void removeNode(Node<K, V> node, RemovalCause cause, List<Removal<K, V>> notifications) {
        entries.remove(node.key);
        strategy.onRemove(node);
        notifications.add(new Removal<>(node.key, node.value, cause));
    }

    private void fireNotifications(List<Removal<K, V>> notifications) {
        if (listener == null || notifications.isEmpty()) {
            return;
        }
        for (Removal<K, V> removal : notifications) {
            try {
                listener.onRemoval(removal.key(), removal.value(), removal.cause());
            } catch (RuntimeException | Error ignored) {
                // A faulty listener must never break cache operations.
            }
        }
    }

    private static long toNanos(Duration duration) {
        return duration == null ? NO_EXPIRY : duration.toNanos();
    }

    private static long requirePositive(long nanos, String name) {
        if (nanos <= 0) {
            throw new IllegalArgumentException(name + " must be strictly positive");
        }
        return nanos;
    }

    private record Removal<K, V>(K key, V value, RemovalCause cause) {
    }
}

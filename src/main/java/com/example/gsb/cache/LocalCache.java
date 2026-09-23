package com.example.gsb.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A bounded, thread-safe, in-process key/value cache implemented from scratch.
 *
 * <p>Features:
 * <ul>
 *   <li>capacity bound with selectable {@link EvictionStrategy} (LRU or LFU);</li>
 *   <li>expiry after write (TTL) and/or after last access (TTI);</li>
 *   <li>{@link RemovalListener} notifications for every kind of removal;</li>
 *   <li>hit / miss / eviction / expiration counters via {@link #statsSnapshot()}.</li>
 * </ul>
 *
 * <p>Concurrency is provided by a single {@link ReentrantLock}: all map and
 * eviction-index mutations happen under it, while value references are
 * published as {@code volatile}. Removal notifications are dispatched after
 * unlocking so that listeners can safely re-enter the cache.
 *
 * <p>Expired entries are removed lazily on access and opportunistically when
 * a put needs room; there is no background cleanup thread. {@link #cleanUp()}
 * can be called to force a purge.
 */
public final class LocalCache<K, V> {

    private final int maximumSize;
    private final long ttlNanos;
    private final long ttiNanos;
    private final Ticker ticker;
    private final RemovalListener<K, V> removalListener;

    private final Map<K, CacheEntry<K, V>> entries;
    private final EvictionTracker<K, V> evictionTracker;
    private final ReentrantLock lock = new ReentrantLock();

    private long hitCount;
    private long missCount;
    private long evictionCount;
    private long expirationCount;

    private LocalCache(Builder<K, V> builder) {
        this.maximumSize = builder.maximumSize;
        this.ttlNanos = builder.ttlNanos;
        this.ttiNanos = builder.ttiNanos;
        this.ticker = builder.ticker;
        this.removalListener = builder.removalListener;
        this.entries = new HashMap<>(Math.min(builder.maximumSize, 1024) * 2);
        this.evictionTracker = builder.strategy == EvictionStrategy.LFU
                ? new LfuTracker<>()
                : new LruTracker<>();
    }

    /**
     * Returns the cached value, or {@code null} if absent or expired.
     *
     * <p>A live hit refreshes TTI; a discovered-but-expired entry is removed
     * (notification cause {@link RemovalCause#EXPIRED}) and counts as a miss.
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        List<Removal<K, V>> pendingNotifications = null;
        V result;
        lock.lock();
        try {
            CacheEntry<K, V> entry = entries.get(key);
            if (entry == null) {
                missCount++;
                return null;
            }
            long now = ticker.readNanos();
            if (entry.isExpiredAt(now, ttlNanos, ttiNanos)) {
                removeEntry(entry, RemovalCause.EXPIRED);
                expirationCount++;
                missCount++;
                pendingNotifications = collect(pendingNotifications, entry, RemovalCause.EXPIRED);
                result = null;
            } else {
                entry.accessNanos = now;
                evictionTracker.recordAccess(entry, now);
                hitCount++;
                result = entry.value;
            }
        } finally {
            lock.unlock();
        }
        fireNotifications(pendingNotifications);
        return result;
    }

    /**
     * Returns the cached value if present, otherwise loads it with
     * {@code loader}, caches and returns it.
     *
     * <p>The loader is invoked outside the cache lock. If several threads miss
     * the same key concurrently, each may invoke the loader; the last stored
     * value wins (see known limitations in the README).
     */
    public V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(loader, "loader");
        V cached = get(key);
        if (cached != null) {
            return cached;
        }
        V loaded = loader.apply(key);
        if (loaded != null) {
            put(key, loaded);
        }
        return loaded;
    }

    /**
     * Inserts or replaces a value, starting fresh TTL/TTI windows.
     *
     * <p>After insertion, if capacity is exceeded, expired entries are purged
     * first and then entries are evicted via the configured strategy until the
     * size is back at the bound. Replacing an existing key never evicts.
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        List<Removal<K, V>> pendingNotifications = new ArrayList<>(2);
        lock.lock();
        try {
            long now = ticker.readNanos();
            CacheEntry<K, V> existing = entries.get(key);
            if (existing != null) {
                if (existing.isExpiredAt(now, ttlNanos, ttiNanos)) {
                    removeEntry(existing, RemovalCause.EXPIRED);
                    expirationCount++;
                    pendingNotifications.add(new Removal<>(existing.key, existing.value, RemovalCause.EXPIRED));
                } else {
                    evictionTracker.remove(existing);
                    pendingNotifications.add(new Removal<>(existing.key, existing.value, RemovalCause.REPLACED));
                }
            }

            CacheEntry<K, V> created = new CacheEntry<>(key, value, now);
            entries.put(key, created);
            evictionTracker.add(created);

            // Enforce capacity after insertion: replacing an existing key must
            // not evict anything, and purging expired entries is preferred to
            // evicting a live one.
            if (entries.size() > maximumSize) {
                int beforePurge = pendingNotifications.size();
                pendingNotifications.addAll(purgeExpiredLocked(now));
                expirationCount += pendingNotifications.size() - beforePurge;
                while (entries.size() > maximumSize) {
                    CacheEntry<K, V> victim = evictionTracker.evict();
                    if (victim == null) {
                        throw new IllegalStateException("eviction tracker returned no victim");
                    }
                    entries.remove(victim.key);
                    evictionCount++;
                    pendingNotifications.add(new Removal<>(victim.key, victim.value, RemovalCause.SIZE));
                }
            }
        } finally {
            lock.unlock();
        }
        fireNotifications(pendingNotifications);
    }

    /** Removes a key if present; reports cause {@link RemovalCause#EXPLICIT}. */
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        CacheEntry<K, V> removed;
        lock.lock();
        try {
            removed = entries.remove(key);
            if (removed != null) {
                evictionTracker.remove(removed);
            }
        } finally {
            lock.unlock();
        }
        if (removed != null) {
            fireNotification(removed.key, removed.value, RemovalCause.EXPLICIT);
        }
    }

    /** Removes every entry; each one reports cause {@link RemovalCause#EXPLICIT}. */
    public void invalidateAll() {
        List<Removal<K, V>> pendingNotifications = new ArrayList<>();
        lock.lock();
        try {
            for (CacheEntry<K, V> entry : entries.values()) {
                pendingNotifications.add(new Removal<>(entry.key, entry.value, RemovalCause.EXPLICIT));
                evictionTracker.remove(entry);
            }
            entries.clear();
        } finally {
            lock.unlock();
        }
        fireNotifications(pendingNotifications);
    }

    /**
     * Purgely removes every currently expired entry (notifications cause
     * {@link RemovalCause#EXPIRED}). Returns the number of removed entries.
     */
    public int cleanUp() {
        List<Removal<K, V>> pendingNotifications;
        lock.lock();
        try {
            pendingNotifications = purgeExpiredLocked(ticker.readNanos());
            expirationCount += pendingNotifications.size();
        } finally {
            lock.unlock();
        }
        fireNotifications(pendingNotifications);
        return pendingNotifications.size();
    }

    /** Number of live entries (expired but not yet purged entries are excluded). */
    public int size() {
        lock.lock();
        try {
            long now = ticker.readNanos();
            int live = 0;
            for (CacheEntry<K, V> entry : entries.values()) {
                if (!entry.isExpiredAt(now, ttlNanos, ttiNanos)) {
                    live++;
                }
            }
            return live;
        } finally {
            lock.unlock();
        }
    }

    /** Whether a live entry exists for the key; does not affect hit/miss counters or TTI. */
    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key");
        List<Removal<K, V>> pendingNotifications = null;
        boolean present;
        lock.lock();
        try {
            CacheEntry<K, V> entry = entries.get(key);
            if (entry == null) {
                present = false;
            } else if (entry.isExpiredAt(ticker.readNanos(), ttlNanos, ttiNanos)) {
                removeEntry(entry, RemovalCause.EXPIRED);
                expirationCount++;
                pendingNotifications = collect(null, entry, RemovalCause.EXPIRED);
                present = false;
            } else {
                present = true;
            }
        } finally {
            lock.unlock();
        }
        fireNotifications(pendingNotifications);
        return present;
    }

    /** Immutable copy of the current counters. */
    public StatsSnapshot statsSnapshot() {
        lock.lock();
        try {
            return new StatsSnapshot(hitCount, missCount, evictionCount, expirationCount);
        } finally {
            lock.unlock();
        }
    }

    // ---- internals (all called with the lock held) ---------------------

    private void removeEntry(CacheEntry<K, V> entry, RemovalCause cause) {
        entries.remove(entry.key);
        evictionTracker.remove(entry);
    }

    private List<Removal<K, V>> purgeExpiredLocked(long now) {
        List<Removal<K, V>> expired = new ArrayList<>();
        Iterator<CacheEntry<K, V>> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            CacheEntry<K, V> entry = iterator.next();
            if (entry.isExpiredAt(now, ttlNanos, ttiNanos)) {
                iterator.remove();
                evictionTracker.remove(entry);
                expired.add(new Removal<>(entry.key, entry.value, RemovalCause.EXPIRED));
            }
        }
        return expired;
    }

    private List<Removal<K, V>> collect(List<Removal<K, V>> pending,
                                        CacheEntry<K, V> entry,
                                        RemovalCause cause) {
        List<Removal<K, V>> notifications = pending != null ? pending : new ArrayList<>(1);
        notifications.add(new Removal<>(entry.key, entry.value, cause));
        return notifications;
    }

    // ---- notification dispatch (always outside the lock) ---------------

    private void fireNotifications(List<Removal<K, V>> notifications) {
        if (notifications == null) {
            return;
        }
        for (Removal<K, V> removal : notifications) {
            fireNotification(removal.key, removal.value, removal.cause);
        }
    }

    private void fireNotification(K key, V value, RemovalCause cause) {
        if (removalListener == null) {
            return;
        }
        try {
            removalListener.onRemoval(new RemovalNotification<>(key, value, cause));
        } catch (RuntimeException | Error ignored) {
            // Listener failures must never break cache reads, writes or eviction.
        }
    }

    /** Creates a new builder. */
    public static <K, V> Builder<K, V> newBuilder() {
        return new Builder<>();
    }

    private record Removal<K, V>(K key, V value, RemovalCause cause) {
    }

    /**
     * Fluent configuration for {@link LocalCache}.
     */
    public static final class Builder<K, V> {

        private int maximumSize = 1024;
        private EvictionStrategy strategy = EvictionStrategy.LRU;
        private long ttlNanos = -1;
        private long ttiNanos = -1;
        private Ticker ticker = Ticker.systemTicker();
        private RemovalListener<K, V> removalListener;

        private Builder() {
        }

        /** Maximum number of live entries; must be a positive integer. */
        public Builder<K, V> maximumSize(int size) {
            this.maximumSize = size;
            return this;
        }

        /** Eviction strategy used once capacity is exceeded (default LRU). */
        public Builder<K, V> evictionStrategy(EvictionStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        /** Time-to-live: an entry expires this long after its last write. */
        public Builder<K, V> expireAfterWrite(Duration duration) {
            this.ttlNanos = requirePositive(duration, "ttl");
            return this;
        }

        /** Time-to-idle: an entry expires this long after its last read or write. */
        public Builder<K, V> expireAfterAccess(Duration duration) {
            this.ttiNanos = requirePositive(duration, "tti");
            return this;
        }

        /** Installs a removal/eviction/expiry callback. */
        public Builder<K, V> removalListener(RemovalListener<K, V> listener) {
            this.removalListener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        // Visible for testing: inject a controllable clock.
        Builder<K, V> ticker(Ticker customTicker) {
            this.ticker = Objects.requireNonNull(customTicker, "ticker");
            return this;
        }

        public LocalCache<K, V> build() {
            if (maximumSize <= 0) {
                throw new IllegalArgumentException("maximumSize must be positive: " + maximumSize);
            }
            if (ttlNanos == 0 || ttiNanos == 0) {
                throw new IllegalArgumentException("expiry durations must be positive");
            }
            return new LocalCache<>(this);
        }

        private static long requirePositive(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " duration must be positive: " + duration);
            }
            return duration.toNanos();
        }
    }
}

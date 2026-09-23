package com.example.gsb.cache;

import java.time.Duration;
import java.util.Objects;

/** Configuration for {@link LocalCache}. Built via the nested builder. */
public final class CacheConfig {

    private final int capacity;
    private final EvictionPolicy evictionPolicy;
    private final Duration ttl;
    private final Duration tti;
    private final EvictionListener<Object, Object> listener;
    private final Ticker ticker;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CacheConfig(Builder<?> b) {
        this.capacity = b.capacity;
        this.evictionPolicy = b.evictionPolicy;
        this.ttl = b.ttl;
        this.tti = b.tti;
        this.listener = (EvictionListener) b.listener;
        this.ticker = b.ticker;
    }

    public int capacity() {
        return capacity;
    }

    public EvictionPolicy evictionPolicy() {
        return evictionPolicy;
    }

    public Duration ttl() {
        return ttl;
    }

    public Duration tti() {
        return tti;
    }

    @SuppressWarnings("unchecked")
    public <K, V> EvictionListener<K, V> listener() {
        return (EvictionListener<K, V>) listener;
    }

    public Ticker ticker() {
        return ticker;
    }

    public static Builder<CacheConfig> newBuilder() {
        return new Builder<>();
    }

    /**
     * Generic builder. The type parameter exists so subclasses of the cache
     * could extend it; plain usage is {@code CacheConfig.newBuilder()...}.
     */
    public static final class Builder<T> {

        private int capacity = 1024;
        private EvictionPolicy evictionPolicy = EvictionPolicy.LRU;
        private Duration ttl;
        private Duration tti;
        private EvictionListener<?, ?> listener;
        private Ticker ticker = Ticker.systemTicker();

        private Builder() {
        }

        public Builder<T> capacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive: " + capacity);
            }
            this.capacity = capacity;
            return this;
        }

        public Builder<T> evictionPolicy(EvictionPolicy policy) {
            this.evictionPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public Builder<T> expireAfterWrite(Duration ttl) {
            requirePositive(ttl, "ttl");
            this.ttl = ttl;
            return this;
        }

        public Builder<T> expireAfterAccess(Duration tti) {
            requirePositive(tti, "tti");
            this.tti = tti;
            return this;
        }

        public <K, V> Builder<T> evictionListener(EvictionListener<K, V> listener) {
            this.listener = Objects.requireNonNull(listener);
            return this;
        }

        /** Exposed mainly for deterministic tests; defaults to the system clock. */
        public Builder<T> ticker(Ticker ticker) {
            this.ticker = Objects.requireNonNull(ticker);
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(this);
        }

        private static void requirePositive(Duration d, String name) {
            if (d != null && !(d.toNanos() > 0)) {
                throw new IllegalArgumentException(name + " must be strictly positive: " + d);
            }
        }
    }
}

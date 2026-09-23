package com.example.gsb.cache;

import java.util.Objects;

/**
 * Immutable notification delivered to a {@link RemovalListener}.
 */
public final class RemovalNotification<K, V> {

    private final K key;
    private final V value;
    private final RemovalCause cause;

    public RemovalNotification(K key, V value, RemovalCause cause) {
        this.key = key;
        this.value = value;
        this.cause = Objects.requireNonNull(cause, "cause");
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    public RemovalCause getCause() {
        return cause;
    }

    @Override
    public String toString() {
        return "RemovalNotification{key=" + key + ", cause=" + cause + "}";
    }
}

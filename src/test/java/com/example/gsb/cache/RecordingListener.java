package com.example.gsb.cache;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test listener that remembers every notification and is thread-safe. */
final class RecordingListener<K, V> implements RemovalListener<K, V> {

    private final CopyOnWriteArrayList<RemovalNotification<K, V>> notifications = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<RemovalCause, Integer> causeCounts = new ConcurrentHashMap<>();

    @Override
    public void onRemoval(RemovalNotification<K, V> notification) {
        notifications.add(notification);
        causeCounts.merge(notification.getCause(), 1, Integer::sum);
    }

    List<RemovalNotification<K, V>> notifications() {
        return notifications;
    }

    int count(RemovalCause cause) {
        return causeCounts.getOrDefault(cause, 0);
    }
}

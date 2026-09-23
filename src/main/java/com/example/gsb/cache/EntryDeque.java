package com.example.gsb.cache;

/**
 * Hand-written intrusive doubly linked list of {@link CacheEntry} nodes.
 *
 * <p>The most recently touched entry sits at the head; the coldest entry
 * sits at the tail. All operations are O(1) and only touch the two
 * adjacent pointers of the given node, so the same node type can be linked
 * into this list without any external {@code HashMap.Entry} or
 * {@code LinkedList.Node} wrapper.
 */
final class EntryDeque<K, V> {

    private CacheEntry<K, V> head;
    private CacheEntry<K, V> tail;

    /** Links {@code entry} as the newest node. Caller guarantees it is not already linked. */
    void addFirst(CacheEntry<K, V> entry) {
        entry.prev = null;
        entry.next = head;
        if (head != null) {
            head.prev = entry;
        } else {
            tail = entry;
        }
        head = entry;
    }

    /** Unlinks {@code entry}; a no-op if it is not linked into this list. */
    void remove(CacheEntry<K, V> entry) {
        CacheEntry<K, V> previous = entry.prev;
        CacheEntry<K, V> following = entry.next;
        if (previous != null) {
            previous.next = following;
        } else if (entry == head) {
            head = following;
        }
        if (following != null) {
            following.prev = previous;
        } else if (entry == tail) {
            tail = previous;
        }
        entry.prev = null;
        entry.next = null;
    }

    /** Coldest node, or {@code null} when the list is empty. */
    CacheEntry<K, V> peekLast() {
        return tail;
    }

    boolean isEmpty() {
        return head == null;
    }
}

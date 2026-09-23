package com.example.gsb.cache.internal;

/**
 * O(1) LRU implemented with a hand-rolled doubly-linked list.
 *
 * <p>The most recently inserted/accessed node is at the head; the least
 * recently used node is at the tail and is the eviction candidate.
 */
public final class LruStrategy<K, V> implements EvictionStrategy<K, V> {

    private Node<K, V> head;
    private Node<K, V> tail;

    @Override
    public void onInsert(Node<K, V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    @Override
    public void onAccess(Node<K, V> node) {
        moveToHead(node);
    }

    @Override
    public void onRemove(Node<K, V> node) {
        unlink(node);
    }

    @Override
    public Node<K, V> evictionCandidate() {
        return tail;
    }

    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return;
        }
        unlink(node);
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    private void unlink(Node<K, V> node) {
        Node<K, V> p = node.prev;
        Node<K, V> n = node.next;
        if (p != null) {
            p.next = n;
        } else if (head == node) {
            head = n;
        }
        if (n != null) {
            n.prev = p;
        } else if (tail == node) {
            tail = p;
        }
        node.prev = null;
        node.next = null;
    }
}

package com.example.gsb.cache;

import java.util.concurrent.atomic.AtomicLong;

/** Manually-advanced clock for deterministic expiry tests. */
public final class FakeTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long readNanos() {
        return nanos.get();
    }

    public void advance(long deltaNanos) {
        nanos.addAndGet(deltaNanos);
    }

    public void advance(java.time.Duration duration) {
        advance(duration.toNanos());
    }
}

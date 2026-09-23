package com.example.gsb.cache;

import java.util.concurrent.atomic.AtomicLong;

/** Controllable clock for deterministic expiry tests. */
final class ManualTicker implements Ticker {

    private final AtomicLong nanos = new AtomicLong();

    @Override
    public long readNanos() {
        return nanos.get();
    }

    void advanceMillis(long millis) {
        nanos.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis));
    }

    void advanceSeconds(long seconds) {
        nanos.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds));
    }
}

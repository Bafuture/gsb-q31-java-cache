package com.example.gsb.cache;

/**
 * Source of monotonically increasing time values (in nanoseconds).
 *
 * <p>Production code uses {@link System#nanoTime()}; tests inject a
 * manually advanced ticker to exercise expiry deterministically.
 */
@FunctionalInterface
public interface Ticker {

    long readNanos();

    /** Ticker backed by {@link System#nanoTime()}. */
    static Ticker systemTicker() {
        return System::nanoTime;
    }
}

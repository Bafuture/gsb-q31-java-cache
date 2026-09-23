package com.example.gsb.cache;

/**
 * Monotonic time source used for all expiry calculations.
 *
 * <p>The implementation only relies on the relative distance between readings,
 * never on their absolute value.
 */
@FunctionalInterface
public interface Ticker {

    long readNanos();

    static Ticker systemTicker() {
        return System::nanoTime;
    }
}

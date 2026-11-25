package com.successor.engine.algo;

/**
 * Counts elementary steps (comparisons, edge scans, probes) so algorithms can be compared by machine-independent work
 * as well as by time. Not thread-safe: use one per run.
 */
public final class Work {
    private long count;

    public static Work counter() {
        return new Work();
    }

    public void add(long n) {
        count += n;
    }

    public void inc() {
        count++;
    }

    public long get() {
        return count;
    }

    @Override
    public String toString() {
        return Long.toString(count);
    }
}

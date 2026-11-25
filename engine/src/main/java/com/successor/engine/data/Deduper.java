package com.successor.engine.data;

import com.successor.engine.algo.BloomFilter;
import com.successor.engine.algo.Work;

import java.util.HashSet;
import java.util.Set;

/**
 * Drops repeated records while loading. A Bloom filter answers first: "definitely new" needs no further check;
 * "possibly seen" is confirmed against the exact key set, so a false positive never drops a real record.
 */
public final class Deduper {
    private final BloomFilter bloom;
    private final Set<String> exact = new HashSet<>();
    private final Work work = Work.counter();
    private long definitelyNew;
    private long duplicates;
    private long falsePositives;

    public Deduper(long expected, double fpp) {
        this.bloom = BloomFilter.forCapacity(Math.max(1, expected), fpp);
    }

    /** True the first time a key is offered, false for every repeat. */
    public boolean firstTime(String key) {
        if (!bloom.mightContain(key, work)) {
            definitelyNew++;
        } else if (exact.contains(key)) {
            duplicates++;
            return false;
        } else {
            falsePositives++;
        }
        bloom.add(key);
        exact.add(key);
        return true;
    }

    public long definitelyNew() {
        return definitelyNew;
    }

    public long duplicates() {
        return duplicates;
    }

    public long falsePositives() {
        return falsePositives;
    }

    /** Exact look-ups the Bloom filter saved (keys it proved new). */
    public long exactChecksAvoided() {
        return definitelyNew;
    }

    public long bloomProbes() {
        return work.get();
    }

    public BloomFilter bloom() {
        return bloom;
    }
}

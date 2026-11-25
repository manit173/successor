package com.successor.engine.algo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterTest {

    @Test
    void neverAFalseNegative() {
        BloomFilter b = BloomFilter.forCapacity(50_000, 0.01);
        for (int i = 0; i < 50_000; i++) {
            b.add("player:" + i + ":2019:Lyon");
        }
        for (int i = 0; i < 50_000; i++) {
            assertTrue(b.mightContain("player:" + i + ":2019:Lyon"), "added key reported absent: " + i);
        }
    }

    @Test
    void falsePositiveRateMatchesTheoryAtCapacity() {
        int n = 100_000;
        BloomFilter b = BloomFilter.forCapacity(n, 0.01);
        for (int i = 0; i < n; i++) {
            b.add("in-" + i);
        }
        int fp = 0;
        int probes = 200_000;
        for (int i = 0; i < probes; i++) {
            if (b.mightContain("out-" + i)) {
                fp++;
            }
        }
        double rate = (double) fp / probes;
        assertEquals(b.expectedFpp(), rate, 0.004, "measured " + rate + " vs theory " + b.expectedFpp());
        assertTrue(rate < 0.015);
    }

    @Test
    void sizingFollowsTheFormula() {
        BloomFilter b = BloomFilter.forCapacity(1_000_000, 0.01);
        // m = −n ln p / (ln 2)² ≈ 9.585 n bits; k = (m/n) ln 2 ≈ 6.64 → 7
        assertEquals(9_585_059, b.bitSize(), 1);
        assertEquals(7, b.hashCount());
        assertTrue(b.sizeBytes() < 1_300_000, "≈ 1.2 MB for a million keys");
    }

    @Test
    void edgeCaseEmptyFilterAndUnicodeKeys() {
        BloomFilter b = BloomFilter.forCapacity(10, 0.01);
        assertFalse(b.mightContain(""));
        assertFalse(b.mightContain("Ødegaard"));
        b.add("Ødegaard");
        b.add("");
        assertTrue(b.mightContain("Ødegaard"));
        assertTrue(b.mightContain(""));
        assertEquals(2, b.inserted());
    }

    @Test
    void workCountsAtMostKProbesPerQuery() {
        BloomFilter b = BloomFilter.forCapacity(1000, 0.01);
        b.add("x");
        Work w = Work.counter();
        b.mightContain("x", w);
        assertEquals(b.hashCount(), w.get(), "a present key checks all k bits");
        Work w2 = Work.counter();
        b.mightContain("definitely-not-there", w2);
        assertTrue(w2.get() >= 1 && w2.get() <= b.hashCount());
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forCapacity(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forCapacity(10, 0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forCapacity(10, 1));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(64, 0));
    }
}

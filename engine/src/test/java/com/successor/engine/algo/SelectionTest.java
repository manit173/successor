package com.successor.engine.algo;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectionTest {

    /** Random, many duplicates, all equal, sorted, reversed: the inputs that break naive quickselects. */
    private static double[][] inputs(Random rnd, int n) {
        double[] random = new double[n];
        double[] dupes = new double[n];
        double[] equal = new double[n];
        double[] sortedUp = new double[n];
        double[] sortedDown = new double[n];
        for (int i = 0; i < n; i++) {
            random[i] = rnd.nextDouble() * 100;
            dupes[i] = rnd.nextInt(3);
            equal[i] = 4.2;
            sortedUp[i] = i;
            sortedDown[i] = n - i;
        }
        return new double[][]{random, dupes, equal, sortedUp, sortedDown};
    }

    @Test
    void quickselectAndMedianOfMediansEqualTheSortedOrderStatistic() {
        Random rnd = new Random(1);
        for (int n : new int[]{1, 2, 5, 11, 100, 1001}) {
            for (double[] a : inputs(rnd, n)) {
                double[] sorted = a.clone();
                Arrays.sort(sorted);
                for (int k : new int[]{0, n / 2, n - 1, rnd.nextInt(n)}) {
                    assertEquals(sorted[k], Selection.select(a, k, rnd, Work.counter()), "quickselect n=" + n);
                    int[] idx = Selection.identity(n);
                    int pos = Selection.medianOfMedians(a, idx, k, Work.counter());
                    assertEquals(sorted[k], a[idx[pos]], "median of medians n=" + n);
                }
            }
        }
    }

    @Test
    void topKMatchesSortingAndTheHeapBaseline() {
        Random rnd = new Random(2);
        for (int trial = 0; trial < 200; trial++) {
            int n = rnd.nextInt(300);
            double[] keys = new double[n];
            for (int i = 0; i < n; i++) {
                keys[i] = rnd.nextInt(50); // duplicates on purpose: ties break by index
            }
            int k = rnd.nextInt(n + 3);
            int[] bySort = Selection.sortTopK(keys, k, Work.counter());
            assertArrayEquals(bySort, Selection.topK(keys, k, rnd, Work.counter()), "quickselect top-k");
            assertArrayEquals(bySort, Selection.heapTopK(keys, k, Work.counter()), "heap top-k");
        }
    }

    @Test
    void expectedLinearWorkEvenOnSortedAndDuplicateHeavyInput() {
        Random rnd = new Random(3);
        int n = 200_000;
        for (double[] a : inputs(rnd, n)) {
            Work w = Work.counter();
            Selection.select(a, n / 2, rnd, w);
            assertTrue(w.get() < 6L * n, "≈ linear: " + w.get() / (double) n + " comparisons per element");
        }
    }

    @Test
    void quantilesInterpolateLikeSpreadsheets() {
        double[] v = {7, 1, 3, 5};
        Random rnd = new Random(4);
        assertEquals(1, Selection.quantile(v, 0, rnd, Work.counter()));
        assertEquals(7, Selection.quantile(v, 1, rnd, Work.counter()));
        assertEquals(4, Selection.quantile(v, 0.5, rnd, Work.counter()), 1e-12); // between 3 and 5
        assertEquals(2.5, Selection.quantile(v, 0.25, rnd, Work.counter()), 1e-12); // 1 + 0.75·(3−1)
        assertArrayEquals(new double[]{7, 1, 3, 5}, v, "input untouched");
    }

    @Test
    void edgeCasesSingleElementKZeroAndKLargerThanN() {
        Random rnd = new Random(5);
        assertEquals(9.5, Selection.select(new double[]{9.5}, 0, rnd, Work.counter()));
        assertEquals(0, Selection.topK(new double[]{3, 1}, 0, rnd, Work.counter()).length);
        assertArrayEquals(new int[]{1, 0}, Selection.topK(new double[]{3, 1}, 10, rnd, Work.counter()),
                "k > n returns all, ordered");
        assertEquals(0, Selection.heapTopK(new double[0], 5, Work.counter()).length);
    }

    @Test
    void invalidInputIsRejected() {
        Random rnd = new Random(6);
        assertThrows(IllegalArgumentException.class, () -> Selection.select(new double[0], 0, rnd, Work.counter()));
        assertThrows(IllegalArgumentException.class, () -> Selection.select(new double[]{1, 2}, 2, rnd, Work.counter()));
        assertThrows(IllegalArgumentException.class, () -> Selection.select(new double[]{1, Double.NaN}, 0, rnd,
                Work.counter()));
        assertThrows(IllegalArgumentException.class, () -> Selection.topK(new double[]{1}, -1, rnd, Work.counter()));
        assertThrows(IllegalArgumentException.class, () -> Selection.quantile(new double[]{1}, 1.5, rnd,
                Work.counter()));
    }
}

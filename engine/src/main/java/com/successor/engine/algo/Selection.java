package com.successor.engine.algo;

import java.util.Arrays;
import java.util.Random;

/**
 * Selection: the k-th smallest of n values without sorting all of them, and the k smallest (top-k).
 *
 * <p><b>Randomized quickselect</b> (the method the app uses): pick a pivot uniformly at random, split the range into
 * {@code < pivot | = pivot | > pivot} (three-way, so runs of equal values such as many zeros cost nothing extra), and
 * continue only in the part that holds position k.
 * <pre>
 *   select(a, k):  while true
 *       p = a[random index in lo..hi]
 *       partition lo..hi into  &lt; p  |  = p  |  &gt; p    → bounds lt, gt
 *       if k &lt; lt: hi = lt − 1   else if k &gt; gt: lo = gt + 1   else return p
 * </pre>
 * <b>Expected O(n):</b> a random pivot lands in the middle half with probability ½, which shrinks the range to at most
 * ¾ of its size; the expected work is a geometric series ≤ 4n comparisons per such step, so O(n) overall. The worst
 * case is O(n²), but it needs consistently unlucky random pivots, whatever the input order (an adversary who does
 * not see the random numbers cannot force it).
 *
 * <p>Baselines: {@link #medianOfMedians} (deterministic O(n) worst case, but a larger constant), {@link #heapTopK}
 * (binary max-heap of size k, O(n log k)), and sorting everything (O(n log n)).
 *
 * <p>All methods work on an index array {@code idx} ordered by {@code keys[idx[i]]}, so callers keep track of which
 * player each value belongs to. Keys must not be NaN. Ties are broken by index, so results are deterministic for a
 * given random seed.
 */
public final class Selection {
    private Selection() {
    }

    /** k-th smallest value (0-based) of {@code values}; {@code values} itself is not modified. */
    public static double select(double[] values, int k, Random rnd, Work work) {
        int[] idx = identity(values.length);
        int pos = selectIndex(values, idx, k, rnd, work);
        return values[idx[pos]];
    }

    /**
     * Rearranges {@code idx} so that position k holds the index with the k-th smallest key, smaller ones before it and
     * larger ones after it (each side in no particular order). Returns k.
     */
    public static int selectIndex(double[] keys, int[] idx, int k, Random rnd, Work work) {
        check(keys, idx, k);
        int lo = 0;
        int hi = idx.length - 1;
        while (lo < hi) {
            int p = idx[lo + rnd.nextInt(hi - lo + 1)];
            // three-way partition (Dijkstra): [lo, lt) < p, [lt, i) == p, (gt, hi] > p
            int lt = lo;
            int gt = hi;
            int i = lo;
            while (i <= gt) {
                work.inc();
                int c = compare(keys, idx[i], p);
                if (c < 0) {
                    swap(idx, lt++, i++);
                } else if (c > 0) {
                    swap(idx, i, gt--);
                } else {
                    i++;
                }
            }
            if (k < lt) {
                hi = lt - 1;
            } else if (k > gt) {
                lo = gt + 1;
            } else {
                return k;
            }
        }
        return k;
    }

    /** Indices of the k smallest keys, in ascending order: quickselect O(n) + sorting only the k winners. */
    public static int[] topK(double[] keys, int k, Random rnd, Work work) {
        int n = keys.length;
        if (k < 0) {
            throw new IllegalArgumentException("k must be ≥ 0, got " + k);
        }
        int[] idx = identity(n);
        int m = Math.min(k, n);
        if (m == 0) {
            return new int[0];
        }
        if (m < n) {
            selectIndex(keys, idx, m - 1, rnd, work);
        }
        Integer[] best = new Integer[m];
        for (int i = 0; i < m; i++) {
            best[i] = idx[i];
        }
        Arrays.sort(best, (a, b) -> {
            work.inc();
            return compare(keys, a, b);
        });
        int[] out = new int[m];
        for (int i = 0; i < m; i++) {
            out[i] = best[i];
        }
        return out;
    }

    /**
     * The q-quantile (0 ≤ q ≤ 1) of {@code values} by linear interpolation between order statistics (the definition
     * spreadsheets use). Two quickselects at most; {@code values} is not modified.
     */
    public static double quantile(double[] values, double q, Random rnd, Work work) {
        if (values.length == 0) {
            throw new IllegalArgumentException("quantile of an empty set is undefined");
        }
        if (!(q >= 0 && q <= 1)) {
            throw new IllegalArgumentException("q must be in [0, 1], got " + q);
        }
        double h = q * (values.length - 1);
        int lo = (int) Math.floor(h);
        double a = select(values, lo, rnd, work);
        if (h == lo) {
            return a;
        }
        double b = select(values, lo + 1, rnd, work);
        return a + (h - lo) * (b - a);
    }

    /**
     * Several quantiles at once by <b>multi-select</b>: place the middle requested rank with one quickselect, then
     * recurse on the left part for the smaller ranks and on the right part for the larger ones. For the median and
     * quartiles that is about n + n/2 + n/2 = 2n expected comparisons instead of 3n (or 6n with interpolation done by
     * separate selections). The (r+1)-th smallest needed for interpolation is the minimum of the part right of r.
     */
    public static double[] quantiles(double[] values, double[] qs, Random rnd, Work work) {
        if (values.length == 0) {
            throw new IllegalArgumentException("quantiles of an empty set are undefined");
        }
        int n = values.length;
        int[] idx = identity(n);
        for (double v : values) {
            if (Double.isNaN(v)) {
                throw new IllegalArgumentException("NaN value (missing values must be filtered out)");
            }
        }
        java.util.TreeSet<Integer> rankSet = new java.util.TreeSet<>();
        for (double q : qs) {
            if (!(q >= 0 && q <= 1)) {
                throw new IllegalArgumentException("q must be in [0, 1], got " + q);
            }
            int lo = (int) Math.floor(q * (n - 1));
            rankSet.add(lo);
            if (lo + 1 < n && q * (n - 1) != lo) {
                rankSet.add(lo + 1);
            }
        }
        int[] ranks = rankSet.stream().mapToInt(Integer::intValue).toArray();
        multiSelect(values, idx, 0, n - 1, ranks, 0, ranks.length - 1, rnd, work);
        double[] out = new double[qs.length];
        for (int i = 0; i < qs.length; i++) {
            double h = qs[i] * (n - 1);
            int lo = (int) Math.floor(h);
            double a = values[idx[lo]];
            out[i] = h == lo ? a : a + (h - lo) * (values[idx[lo + 1]] - a);
        }
        return out;
    }

    private static void multiSelect(double[] keys, int[] idx, int lo, int hi, int[] ranks, int rlo, int rhi,
                                    Random rnd, Work work) {
        if (rlo > rhi || lo > hi) {
            return;
        }
        int mid = (rlo + rhi) >>> 1;
        int k = ranks[mid];
        int a = lo;
        int b = hi;
        int lt = a;
        int gt = b;
        while (a < b) {
            int p = idx[a + rnd.nextInt(b - a + 1)];
            lt = a;
            gt = b;
            int i = a;
            while (i <= gt) {
                work.inc();
                int c = compare(keys, idx[i], p);
                if (c < 0) {
                    swap(idx, lt++, i++);
                } else if (c > 0) {
                    swap(idx, i, gt--);
                } else {
                    i++;
                }
            }
            if (k < lt) {
                b = lt - 1;
            } else if (k > gt) {
                a = gt + 1;
            } else {
                break;
            }
        }
        // everything left of k is ≤ it, right of k is ≥ it: smaller ranks only need the left part, larger the right
        multiSelect(keys, idx, lo, k - 1, ranks, rlo, mid - 1, rnd, work);
        multiSelect(keys, idx, k + 1, hi, ranks, mid + 1, rhi, rnd, work);
    }

    // ------------------------------------------------------------------------------------------------ baselines

    /**
     * Deterministic selection (Blum, Floyd, Pratt, Rivest, Tarjan): the pivot is the median of the medians of groups
     * of five, which guarantees at least 30% of the range on each side, so O(n) even in the worst case. Returns the
     * position of the k-th smallest in the rearranged {@code idx}.
     */
    public static int medianOfMedians(double[] keys, int[] idx, int k, Work work) {
        check(keys, idx, k);
        return mom(keys, idx, 0, idx.length - 1, k, work);
    }

    private static int mom(double[] keys, int[] idx, int lo, int hi, int k, Work work) {
        while (true) {
            if (hi - lo < 10) {
                insertionSort(keys, idx, lo, hi, work);
                return k;
            }
            // move each group-of-five median to the front of the range
            int m = lo;
            for (int g = lo; g <= hi; g += 5) {
                int end = Math.min(g + 4, hi);
                insertionSort(keys, idx, g, end, work);
                swap(idx, m++, g + (end - g) / 2);
            }
            int mid = lo + (m - 1 - lo) / 2;
            mom(keys, idx, lo, m - 1, mid, work);
            int p = idx[mid];
            int lt = lo;
            int gt = hi;
            int i = lo;
            while (i <= gt) {
                work.inc();
                int c = compare(keys, idx[i], p);
                if (c < 0) {
                    swap(idx, lt++, i++);
                } else if (c > 0) {
                    swap(idx, i, gt--);
                } else {
                    i++;
                }
            }
            if (k < lt) {
                hi = lt - 1;
            } else if (k > gt) {
                lo = gt + 1;
            } else {
                return k;
            }
        }
    }

    /** Baseline top-k: a binary max-heap holding the k best so far; each new key replaces the root if smaller. */
    public static int[] heapTopK(double[] keys, int k, Work work) {
        if (k < 0) {
            throw new IllegalArgumentException("k must be ≥ 0, got " + k);
        }
        int m = Math.min(k, keys.length);
        int[] heap = new int[m];
        int size = 0;
        for (int i = 0; i < keys.length; i++) {
            if (Double.isNaN(keys[i])) {
                throw new IllegalArgumentException("NaN key at " + i);
            }
            if (size < m) {
                heap[size] = i;
                siftUp(keys, heap, size++, work);
            } else if (m > 0) {
                work.inc();
                if (compare(keys, i, heap[0]) < 0) {
                    heap[0] = i;
                    siftDown(keys, heap, 0, size, work);
                }
            }
        }
        // pop in descending order to produce ascending output
        int[] out = new int[size];
        for (int end = size - 1; end >= 0; end--) {
            out[end] = heap[0];
            heap[0] = heap[end];
            siftDown(keys, heap, 0, end, work);
        }
        return out;
    }

    /** Baseline: sort all indices, take the first k. O(n log n). */
    public static int[] sortTopK(double[] keys, int k, Work work) {
        Integer[] all = new Integer[keys.length];
        for (int i = 0; i < keys.length; i++) {
            if (Double.isNaN(keys[i])) {
                throw new IllegalArgumentException("NaN key at " + i);
            }
            all[i] = i;
        }
        Arrays.sort(all, (a, b) -> {
            work.inc();
            return compare(keys, a, b);
        });
        int m = Math.min(Math.max(k, 0), keys.length);
        int[] out = new int[m];
        for (int i = 0; i < m; i++) {
            out[i] = all[i];
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void siftUp(double[] keys, int[] h, int i, Work work) {
        while (i > 0) {
            int parent = (i - 1) / 2;
            work.inc();
            if (compare(keys, h[i], h[parent]) <= 0) {
                return;
            }
            swap(h, i, parent);
            i = parent;
        }
    }

    private static void siftDown(double[] keys, int[] h, int i, int size, Work work) {
        while (true) {
            int l = 2 * i + 1;
            if (l >= size) {
                return;
            }
            int big = l;
            if (l + 1 < size) {
                work.inc();
                if (compare(keys, h[l + 1], h[l]) > 0) {
                    big = l + 1;
                }
            }
            work.inc();
            if (compare(keys, h[big], h[i]) <= 0) {
                return;
            }
            swap(h, i, big);
            i = big;
        }
    }

    private static void insertionSort(double[] keys, int[] idx, int lo, int hi, Work work) {
        for (int i = lo + 1; i <= hi; i++) {
            int x = idx[i];
            int j = i - 1;
            while (j >= lo) {
                work.inc();
                if (compare(keys, idx[j], x) <= 0) {
                    break;
                }
                idx[j + 1] = idx[j];
                j--;
            }
            idx[j + 1] = x;
        }
    }

    /** Orders by key, then by index (a total order, so ties are deterministic). */
    static int compare(double[] keys, int a, int b) {
        int c = Double.compare(keys[a], keys[b]);
        return c != 0 ? c : Integer.compare(a, b);
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }

    static int[] identity(int n) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        return idx;
    }

    private static void check(double[] keys, int[] idx, int k) {
        if (idx.length == 0) {
            throw new IllegalArgumentException("cannot select from an empty set");
        }
        if (k < 0 || k >= idx.length) {
            throw new IllegalArgumentException("k = " + k + " is outside 0.." + (idx.length - 1));
        }
        for (int i : idx) {
            if (Double.isNaN(keys[i])) {
                throw new IllegalArgumentException("NaN key at index " + i + " (missing values must be filtered out)");
            }
        }
    }
}

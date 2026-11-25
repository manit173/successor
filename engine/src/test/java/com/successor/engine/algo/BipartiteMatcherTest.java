package com.successor.engine.algo;

import com.successor.engine.algo.BipartiteMatcher.Edge;
import com.successor.engine.algo.BipartiteMatcher.Matching;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BipartiteMatcherTest {

    @Test
    void greedyBaselineMissesAPairThatMaximumMatchingFinds() {
        // A–1 is the single best edge; taking it leaves B with nobody. Matching gives A–2 and B–1.
        List<Edge> e = List.of(new Edge(0, 0, 0.95), new Edge(0, 1, 0.90), new Edge(1, 0, 0.90));
        assertEquals(1, BipartiteMatcher.greedy(2, 2, e).size());
        Matching m = BipartiteMatcher.maximum(2, 2, e);
        assertEquals(2, m.size());
        assertArrayEquals(new int[]{1, 0}, m.rightOf());
    }

    @Test
    void maximumMatchesBruteForceOnRandomGraphs() {
        Random rnd = new Random(11);
        for (int trial = 0; trial < 300; trial++) {
            int l = rnd.nextInt(7);
            int r = rnd.nextInt(7);
            List<Edge> e = randomEdges(rnd, l, r, 0.35);
            Matching m = BipartiteMatcher.maximum(l, r, e);
            assertValid(m, l, r, e);
            assertEquals(bruteMaximum(l, r, e, 0, new boolean[r]), m.size(), "trial " + trial);
        }
    }

    @Test
    void tieredKeepsConfidentPairsEvenWhenAWeakSwapWouldMatchMore() {
        // strong A–1 (0.9); weak B–1 (0.6). A maximum matching ignoring scores could pick B–1 and A–2 (weak, 0.55).
        List<Edge> e = List.of(new Edge(0, 0, 0.9), new Edge(1, 0, 0.6), new Edge(0, 1, 0.55));
        Matching m = BipartiteMatcher.tiered(2, 2, e, 0.85, 0.5);
        assertEquals(0, m.rightOf()[0], "the confident pair A–1 survives");
        assertEquals(-1, m.rightOf()[1]);
        assertEquals(0.9, m.bottleneck(), 1e-12);
    }

    @Test
    void bottleneckIsOptimalAgainstEveryPermutation() {
        Random rnd = new Random(3);
        for (int trial = 0; trial < 300; trial++) {
            int l = 1 + rnd.nextInt(5);
            int r = l + rnd.nextInt(3);
            List<Edge> e = randomEdges(rnd, l, r, 0.7);
            Matching m = BipartiteMatcher.bottleneck(l, r, e);
            double best = bruteBottleneck(l, r, e, 0, new boolean[r], Double.POSITIVE_INFINITY);
            if (best == Double.NEGATIVE_INFINITY) {
                assertNull(m, "infeasible instance must return null (trial " + trial + ")");
            } else {
                assertNotNull(m, "trial " + trial);
                assertValid(m, l, r, e);
                assertEquals(l, m.size());
                assertEquals(best, m.bottleneck(), 1e-12, "trial " + trial);
                Matching g = BipartiteMatcher.greedy(l, r, e);
                if (g.size() == l) {
                    assertTrue(g.bottleneck() <= m.bottleneck() + 1e-12);
                }
            }
        }
    }

    @Test
    void bottleneckKeepsStrongPairsWhenTheWeakestIsTied() {
        // the weakest pair is 0.89 either way; A should keep its 0.92 partner rather than settle for 0.90
        List<Edge> e = List.of(new Edge(0, 0, 0.92), new Edge(0, 1, 0.90), new Edge(1, 0, 0.89), new Edge(1, 2, 0.89));
        Matching m = BipartiteMatcher.bottleneck(2, 3, e);
        assertEquals(0.89, m.bottleneck(), 1e-12);
        assertEquals(0, m.rightOf()[0], "A keeps the 0.92 pair");
        assertEquals(2, m.rightOf()[1]);
    }

    @Test
    void edgeCasesEmptySetsAndInfeasibleVacancies() {
        assertEquals(0, BipartiteMatcher.maximum(0, 0, List.of()).size());
        assertEquals(0, BipartiteMatcher.maximum(3, 2, List.of()).size());
        assertEquals(0, BipartiteMatcher.bottleneck(0, 4, List.of()).size());
        // two vacancies but only one candidate fits either of them
        assertNull(BipartiteMatcher.bottleneck(2, 3, List.of(new Edge(0, 0, 0.9), new Edge(1, 0, 0.8))));
    }

    @Test
    void invalidEdgesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BipartiteMatcher.maximum(1, 1, List.of(new Edge(1, 0, 1))));
        assertThrows(IllegalArgumentException.class, () -> BipartiteMatcher.maximum(1, 1, List.of(new Edge(0, 0, Double.NaN))));
        assertThrows(IllegalArgumentException.class, () -> BipartiteMatcher.greedy(-1, 1, List.of()));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static List<Edge> randomEdges(Random rnd, int l, int r, double density) {
        List<Edge> e = new ArrayList<>();
        for (int i = 0; i < l; i++) {
            for (int j = 0; j < r; j++) {
                if (rnd.nextDouble() < density) {
                    e.add(new Edge(i, j, Math.round(rnd.nextDouble() * 20) / 20.0));
                }
            }
        }
        return e;
    }

    private static void assertValid(Matching m, int l, int r, List<Edge> e) {
        Set<Integer> used = new HashSet<>();
        Set<String> allowed = new HashSet<>();
        e.forEach(x -> allowed.add(x.left() + ":" + x.right()));
        int count = 0;
        for (int i = 0; i < l; i++) {
            int j = m.rightOf()[i];
            if (j >= 0) {
                assertTrue(j < r);
                assertTrue(used.add(j), "right vertex used twice");
                assertTrue(allowed.contains(i + ":" + j), "pair not among the edges");
                count++;
            }
        }
        assertEquals(count, m.size());
    }

    private static int bruteMaximum(int l, int r, List<Edge> e, int i, boolean[] used) {
        if (i == l) {
            return 0;
        }
        int best = bruteMaximum(l, r, e, i + 1, used);
        for (Edge x : e) {
            if (x.left() == i && !used[x.right()]) {
                used[x.right()] = true;
                best = Math.max(best, 1 + bruteMaximum(l, r, e, i + 1, used));
                used[x.right()] = false;
            }
        }
        return best;
    }

    /** Best possible weakest score over all left-perfect assignments; −∞ if none exists. */
    private static double bruteBottleneck(int l, int r, List<Edge> e, int i, boolean[] used, double minSoFar) {
        if (i == l) {
            return minSoFar;
        }
        double best = Double.NEGATIVE_INFINITY;
        for (Edge x : e) {
            if (x.left() == i && !used[x.right()]) {
                used[x.right()] = true;
                best = Math.max(best, bruteBottleneck(l, r, e, i + 1, used, Math.min(minSoFar, x.score())));
                used[x.right()] = false;
            }
        }
        return best;
    }
}

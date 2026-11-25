package com.successor.engine.algo;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxFlowTest {

    @Test
    void textbookNetworkHasFlow23() {
        // CLRS figure 26.1: s=0, v1..v4 = 1..4, t=5
        MaxFlow f = new MaxFlow(6, null);
        f.addEdge(0, 1, 16);
        f.addEdge(0, 2, 13);
        f.addEdge(2, 1, 4);
        f.addEdge(1, 3, 12);
        f.addEdge(3, 2, 9);
        f.addEdge(2, 4, 14);
        f.addEdge(4, 3, 7);
        f.addEdge(3, 5, 20);
        f.addEdge(4, 5, 4);
        assertEquals(23, f.maxFlow(0, 5));
    }

    @Test
    void edgeCaseDisconnectedSinkGetsZeroFlow() {
        MaxFlow f = new MaxFlow(4, null);
        f.addEdge(0, 1, 5);
        f.addEdge(2, 3, 5);
        assertEquals(0, f.maxFlow(0, 3));
    }

    @Test
    void flowEqualsMinCutCapacityOnRandomNetworks() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            int n = 2 + rnd.nextInt(9);
            int m = rnd.nextInt(30);
            int[][] e = new int[m][3];
            MaxFlow f = new MaxFlow(n, null);
            for (int i = 0; i < m; i++) {
                e[i] = new int[]{rnd.nextInt(n), rnd.nextInt(n), rnd.nextInt(10)};
                f.addEdge(e[i][0], e[i][1], e[i][2]);
            }
            long flow = f.maxFlow(0, n - 1);
            boolean[] side = f.minCutSourceSide(0);
            assertTrue(side[0]);
            assertFalse(side[n - 1] && flow >= 0 && side[n - 1], "sink must not be reachable after max flow");
            long cut = 0;
            for (int[] x : e) {
                if (side[x[0]] && !side[x[1]]) {
                    cut += x[2];
                }
            }
            assertEquals(cut, flow, "max-flow = min-cut (trial " + trial + ")");
            assertEquals(bruteForceMinCut(n, e), flow, "brute-force min cut (trial " + trial + ")");
        }
    }

    /** Every s–t cut by enumerating source sides (n ≤ 10): the minimum equals the max flow. */
    private static long bruteForceMinCut(int n, int[][] e) {
        long best = Long.MAX_VALUE;
        for (int mask = 0; mask < (1 << n); mask++) {
            if ((mask & 1) == 0 || (mask & (1 << (n - 1))) != 0) {
                continue;
            }
            long c = 0;
            for (int[] x : e) {
                if ((mask >> x[0] & 1) == 1 && (mask >> x[1] & 1) == 0) {
                    c += x[2];
                }
            }
            best = Math.min(best, c);
        }
        return best;
    }

    @Test
    void parallelAndReverseEdgesAreHandled() {
        MaxFlow f = new MaxFlow(3, null);
        f.addEdge(0, 1, 3);
        f.addEdge(0, 1, 4);
        f.addEdge(1, 0, 10);
        f.addEdge(1, 2, 5);
        int last = f.addEdge(1, 2, 5);
        assertEquals(7, f.maxFlow(0, 2));
        assertTrue(f.flowOn(last) <= 5);
    }

    @Test
    void secondCallContinuesAfterNewEdges() {
        MaxFlow f = new MaxFlow(3, null);
        f.addEdge(0, 1, 2);
        f.addEdge(1, 2, 1);
        assertEquals(1, f.maxFlow(0, 2));
        f.addEdge(1, 2, 5);
        assertEquals(1, f.maxFlow(0, 2), "one more unit fits through 0→1");
    }

    @Test
    void invalidInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MaxFlow(1, null));
        MaxFlow f = new MaxFlow(3, null);
        assertThrows(IllegalArgumentException.class, () -> f.addEdge(0, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> f.addEdge(0, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> f.maxFlow(1, 1));
    }
}

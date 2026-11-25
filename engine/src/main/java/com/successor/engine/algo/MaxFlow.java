package com.successor.engine.algo;

import java.util.Arrays;

/**
 * Maximum flow by <b>Edmonds–Karp</b>: Ford–Fulkerson where every augmenting path is a shortest path (BFS) in the
 * residual graph.
 *
 * <pre>
 *   flow = 0
 *   while BFS finds an s→t path P using edges with residual capacity &gt; 0:
 *       b = min residual capacity on P          // the bottleneck
 *       push b along P (forward edges −b, reverse edges +b)
 *       flow += b
 * </pre>
 *
 * <p><b>Correctness:</b> when no augmenting path exists, the vertices reachable from s in the residual graph form a
 * cut whose capacity equals the flow (max-flow min-cut theorem), so the flow is maximum.
 * <b>Complexity:</b> shortest augmenting paths never get shorter, and each edge is the bottleneck O(V) times, so there
 * are O(VE) augmentations of O(E) each: O(V·E²) time, O(V + E) space. On unit-capacity bipartite graphs the number of
 * augmentations is at most the matching size, so matching costs O(V·E).
 *
 * <p>Edges are stored in flat arrays (edge i and its reverse are i and i^1), which keeps the residual graph compact.
 */
public final class MaxFlow {
    private final int n;
    private int[] head;
    private int[] next;
    private int[] to;
    private long[] cap;
    private int edges;
    private final Work work;

    public MaxFlow(int vertices, Work work) {
        if (vertices < 2) {
            throw new IllegalArgumentException("a flow network needs at least 2 vertices, got " + vertices);
        }
        this.n = vertices;
        this.work = work == null ? Work.counter() : work;
        head = new int[vertices];
        Arrays.fill(head, -1);
        next = new int[16];
        to = new int[16];
        cap = new long[16];
    }

    /** Adds a directed edge {@code u → v} with the given capacity; returns its id (use with {@link #flowOn}). */
    public int addEdge(int u, int v, long capacity) {
        if (u < 0 || u >= n || v < 0 || v >= n) {
            throw new IllegalArgumentException("vertex out of range: " + u + " → " + v + " (n = " + n + ")");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be ≥ 0, got " + capacity);
        }
        ensure(edges + 2);
        int id = edges;
        link(u, v, capacity);
        link(v, u, 0);
        return id;
    }

    private void link(int u, int v, long c) {
        to[edges] = v;
        cap[edges] = c;
        next[edges] = head[u];
        head[u] = edges++;
    }

    private void ensure(int size) {
        if (size > to.length) {
            int m = Math.max(size, to.length * 2);
            next = Arrays.copyOf(next, m);
            to = Arrays.copyOf(to, m);
            cap = Arrays.copyOf(cap, m);
        }
    }

    /** Pushes the maximum flow from {@code s} to {@code t}; can be called again after adding edges (it continues). */
    public long maxFlow(int s, int t) {
        if (s == t) {
            throw new IllegalArgumentException("source and sink must differ");
        }
        long flow = 0;
        int[] parentEdge = new int[n];
        int[] queue = new int[n];
        while (true) {
            Arrays.fill(parentEdge, -1);
            parentEdge[s] = -2;
            int qh = 0;
            int qt = 0;
            queue[qt++] = s;
            while (qh < qt && parentEdge[t] == -1) {
                int u = queue[qh++];
                for (int e = head[u]; e != -1; e = next[e]) {
                    work.inc();
                    int v = to[e];
                    if (cap[e] > 0 && parentEdge[v] == -1) {
                        parentEdge[v] = e;
                        queue[qt++] = v;
                    }
                }
            }
            if (parentEdge[t] == -1) {
                return flow; // no augmenting path: the flow is maximum
            }
            long bottleneck = Long.MAX_VALUE;
            for (int v = t; v != s; v = to[parentEdge[v] ^ 1]) {
                bottleneck = Math.min(bottleneck, cap[parentEdge[v]]);
            }
            for (int v = t; v != s; v = to[parentEdge[v] ^ 1]) {
                cap[parentEdge[v]] -= bottleneck;
                cap[parentEdge[v] ^ 1] += bottleneck;
            }
            flow += bottleneck;
        }
    }

    /** Flow currently on the edge returned by {@link #addEdge} (= residual capacity of its reverse). */
    public long flowOn(int edgeId) {
        return cap[edgeId ^ 1];
    }

    /** After {@link #maxFlow}: true for vertices on the source side of a minimum cut (reachable in the residual). */
    public boolean[] minCutSourceSide(int s) {
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int sp = 0;
        stack[sp++] = s;
        seen[s] = true;
        while (sp > 0) {
            int u = stack[--sp];
            for (int e = head[u]; e != -1; e = next[e]) {
                if (cap[e] > 0 && !seen[to[e]]) {
                    seen[to[e]] = true;
                    stack[sp++] = to[e];
                }
            }
        }
        return seen;
    }

    public int vertices() {
        return n;
    }

    public Work work() {
        return work;
    }
}

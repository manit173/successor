package com.successor.engine.algo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * One-to-one assignment between two sets (left = e.g. players from one source, right = players from another, or
 * vacated roles and candidate replacements) by <b>bipartite matching reduced to max-flow</b>.
 *
 * <pre>
 *   source → every left vertex   (capacity 1)
 *   left i → right j             (capacity 1, for every allowed pair)
 *   every right vertex → sink    (capacity 1)
 * </pre>
 * An integral maximum flow is a maximum matching: capacities of 1 allow each vertex at most one partner, and
 * Edmonds–Karp finds integral flows. Time O(V·E) here (each augmentation adds one pair; each BFS is O(V + E)).
 *
 * <p>Three ways to use it:
 * <ul>
 *   <li>{@link #maximum}: the largest set of pairs among the allowed edges.</li>
 *   <li>{@link #tiered}: confident pairs first. Run a maximum matching on edges scoring ≥ the first threshold, freeze
 *       it, then match what is left using the next threshold, and so on. Used for record linkage, where a strong match
 *       should never be traded for two weak ones.</li>
 *   <li>{@link #bottleneck}: match every left vertex while making the <em>weakest</em> pair as strong as possible.
 *       Binary search over the distinct scores τ for the largest τ whose edges (score ≥ τ) still admit a perfect
 *       matching of the left side. Feasibility is monotone in τ, so the search is correct. Cost: O(log E) matchings.
 *       Among the many assignments with that weakest pair, strong pairs are then preferred (edges added from the
 *       highest score down, extended by augmenting paths). Used by the squad planner.</li>
 * </ul>
 * {@link #greedy} (best score first, never revisited) is the baseline: it can match fewer pairs than {@link #maximum}
 * and get a worse weakest pair than {@link #bottleneck}.
 */
public final class BipartiteMatcher {
    private BipartiteMatcher() {
    }

    /** A candidate pair with a score (higher = better). */
    public record Edge(int left, int right, double score) {
    }

    /**
     * Result: {@code rightOf[i]} is the right partner of left i, or −1. {@code bottleneck} is the lowest score among
     * the chosen pairs (NaN if none).
     */
    public record Matching(int[] rightOf, int size, double bottleneck, long work) {
        public int[] leftOf(int rightCount) {
            int[] l = new int[rightCount];
            Arrays.fill(l, -1);
            for (int i = 0; i < rightOf.length; i++) {
                if (rightOf[i] >= 0) {
                    l[rightOf[i]] = i;
                }
            }
            return l;
        }
    }

    /** Maximum-cardinality matching using every given edge (scores ignored). */
    public static Matching maximum(int leftCount, int rightCount, List<Edge> edges) {
        validate(leftCount, rightCount, edges);
        int[] rightOf = new int[leftCount];
        Arrays.fill(rightOf, -1);
        Work work = Work.counter();
        int size = augment(leftCount, rightCount, edges, rightOf, new boolean[rightCount], work);
        return new Matching(rightOf, size, minScore(rightOf, edges), work.get());
    }

    /**
     * Confidence-tiered matching: for each threshold (descending), a maximum matching among still-unmatched vertices
     * using edges with score ≥ threshold; earlier pairs are kept fixed.
     */
    public static Matching tiered(int leftCount, int rightCount, List<Edge> edges, double... thresholds) {
        validate(leftCount, rightCount, edges);
        double[] ts = thresholds.clone();
        Arrays.sort(ts);
        int[] rightOf = new int[leftCount];
        Arrays.fill(rightOf, -1);
        boolean[] rightTaken = new boolean[rightCount];
        Work work = Work.counter();
        int size = 0;
        for (int k = ts.length - 1; k >= 0; k--) {
            double t = ts[k];
            List<Edge> tier = new ArrayList<>();
            for (Edge e : edges) {
                if (e.score() >= t && rightOf[e.left()] == -1 && !rightTaken[e.right()]) {
                    tier.add(e);
                }
            }
            size += augment(leftCount, rightCount, tier, rightOf, rightTaken, work);
        }
        return new Matching(rightOf, size, minScore(rightOf, edges), work.get());
    }

    /**
     * Matches <em>every</em> left vertex, maximising the minimum score of the chosen pairs. Returns {@code null} if
     * even with all edges some left vertex cannot be matched (infeasible: e.g. more vacancies than suitable players).
     */
    public static Matching bottleneck(int leftCount, int rightCount, List<Edge> edges) {
        validate(leftCount, rightCount, edges);
        Work work = Work.counter();
        if (leftCount == 0) {
            return new Matching(new int[0], 0, Double.NaN, 0);
        }
        double[] scores = edges.stream().mapToDouble(Edge::score).distinct().sorted().toArray();
        // feasibility is monotone: if edges ≥ τ admit a left-perfect matching, so do edges ≥ τ' for any τ' < τ
        int lo = 0;
        int hi = scores.length - 1;
        double tau = Double.NaN;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int[] rightOf = perfectOrNull(leftCount, rightCount, edges, scores[mid], work);
            if (rightOf != null) {
                tau = scores[mid];
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (Double.isNaN(tau)) {
            return null;
        }
        // many assignments reach the best weakest pair; prefer the one that keeps strong pairs
        int[] best = preferStrong(leftCount, rightCount, edges, tau, work);
        return new Matching(best, leftCount, minScore(best, edges), work.get());
    }

    /**
     * Among the left-perfect matchings on edges ≥ τ, one that favours strong pairs: add the edges from the highest
     * score down and, after each score level, extend the matching by augmenting paths (the Ford–Fulkerson step on the
     * unit-capacity network, run incrementally). An augmenting path never unmatches a vertex, so a pair formed early
     * is only re-paired when that is the only way to fill another role; and since a perfect matching exists on edges
     * ≥ τ, the final matching is perfect (a matching with no augmenting path is maximum).
     */
    private static int[] preferStrong(int leftCount, int rightCount, List<Edge> edges, double tau, Work work) {
        List<Edge> sorted = new ArrayList<>();
        for (Edge e : edges) {
            if (e.score() >= tau) {
                sorted.add(e);
            }
        }
        sorted.sort(Comparator.comparingDouble(Edge::score).reversed());
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < leftCount; i++) {
            adj.add(new ArrayList<>());
        }
        int[] rightOf = new int[leftCount];
        int[] leftOf = new int[rightCount];
        Arrays.fill(rightOf, -1);
        Arrays.fill(leftOf, -1);
        int[] seen = new int[rightCount];
        int[] stamp = {0};
        int k = 0;
        while (k < sorted.size()) {
            double level = sorted.get(k).score();
            while (k < sorted.size() && sorted.get(k).score() == level) {
                Edge e = sorted.get(k++);
                adj.get(e.left()).add(e.right());
            }
            for (int i = 0; i < leftCount; i++) {
                if (rightOf[i] == -1) {
                    stamp[0]++;
                    augmentFrom(i, adj, rightOf, leftOf, seen, stamp[0], work);
                }
            }
        }
        return rightOf;
    }

    /** Depth-first search for an augmenting path from free left vertex u (Kuhn's form of the augmenting step). */
    private static boolean augmentFrom(int u, List<List<Integer>> adj, int[] rightOf, int[] leftOf, int[] seen,
                                       int stamp, Work work) {
        // a free partner first (strongest first): re-pairing someone else is the last resort
        for (int v : adj.get(u)) {
            work.inc();
            if (leftOf[v] == -1 && seen[v] != stamp) {
                seen[v] = stamp;
                rightOf[u] = v;
                leftOf[v] = u;
                return true;
            }
        }
        for (int v : adj.get(u)) {
            work.inc();
            if (seen[v] == stamp) {
                continue;
            }
            seen[v] = stamp;
            if (leftOf[v] == -1 || augmentFrom(leftOf[v], adj, rightOf, leftOf, seen, stamp, work)) {
                rightOf[u] = v;
                leftOf[v] = u;
                return true;
            }
        }
        return false;
    }

    /** Baseline: take pairs in descending score order whenever both ends are still free. */
    public static Matching greedy(int leftCount, int rightCount, List<Edge> edges) {
        validate(leftCount, rightCount, edges);
        List<Edge> sorted = new ArrayList<>(edges);
        sorted.sort(Comparator.comparingDouble(Edge::score).reversed());
        int[] rightOf = new int[leftCount];
        Arrays.fill(rightOf, -1);
        boolean[] taken = new boolean[rightCount];
        int size = 0;
        long work = 0;
        for (Edge e : sorted) {
            work++;
            if (rightOf[e.left()] == -1 && !taken[e.right()]) {
                rightOf[e.left()] = e.right();
                taken[e.right()] = true;
                size++;
            }
        }
        return new Matching(rightOf, size, minScore(rightOf, edges), work);
    }

    // ------------------------------------------------------------------------------------------------ internals

    private static int[] perfectOrNull(int leftCount, int rightCount, List<Edge> edges, double tau, Work work) {
        List<Edge> allowed = new ArrayList<>();
        for (Edge e : edges) {
            if (e.score() >= tau) {
                allowed.add(e);
            }
        }
        int[] rightOf = new int[leftCount];
        Arrays.fill(rightOf, -1);
        int size = augment(leftCount, rightCount, allowed, rightOf, new boolean[rightCount], work);
        return size == leftCount ? rightOf : null;
    }

    /**
     * Max-flow on the edges whose endpoints are both free (rightOf[l] == −1, !rightTaken[r]); writes the new pairs into
     * rightOf / rightTaken and returns how many were added.
     */
    private static int augment(int leftCount, int rightCount, List<Edge> edges, int[] rightOf, boolean[] rightTaken,
                               Work work) {
        if (edges.isEmpty()) {
            return 0;
        }
        int s = leftCount + rightCount;
        int t = s + 1;
        MaxFlow f = new MaxFlow(leftCount + rightCount + 2, work);
        boolean[] leftUsed = new boolean[leftCount];
        boolean[] rightUsed = new boolean[rightCount];
        int[] ids = new int[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            if (rightOf[e.left()] != -1 || rightTaken[e.right()]) {
                ids[i] = -1;
                continue;
            }
            ids[i] = f.addEdge(e.left(), leftCount + e.right(), 1);
            leftUsed[e.left()] = true;
            rightUsed[e.right()] = true;
        }
        for (int i = 0; i < leftCount; i++) {
            if (leftUsed[i]) {
                f.addEdge(s, i, 1);
            }
        }
        for (int j = 0; j < rightCount; j++) {
            if (rightUsed[j]) {
                f.addEdge(leftCount + j, t, 1);
            }
        }
        int added = (int) f.maxFlow(s, t);
        for (int i = 0; i < edges.size(); i++) {
            if (ids[i] >= 0 && f.flowOn(ids[i]) == 1) {
                Edge e = edges.get(i);
                rightOf[e.left()] = e.right();
                rightTaken[e.right()] = true;
            }
        }
        return added;
    }

    private static double minScore(int[] rightOf, List<Edge> edges) {
        double min = Double.NaN;
        for (Edge e : edges) {
            if (rightOf[e.left()] == e.right()) {
                min = Double.isNaN(min) ? e.score() : Math.min(min, e.score());
            }
        }
        return min;
    }

    private static void validate(int leftCount, int rightCount, List<Edge> edges) {
        if (leftCount < 0 || rightCount < 0) {
            throw new IllegalArgumentException("set sizes must be ≥ 0");
        }
        for (Edge e : edges) {
            if (e.left() < 0 || e.left() >= leftCount || e.right() < 0 || e.right() >= rightCount) {
                throw new IllegalArgumentException("edge out of range: " + e);
            }
            if (Double.isNaN(e.score())) {
                throw new IllegalArgumentException("edge score is NaN: " + e);
            }
        }
    }
}

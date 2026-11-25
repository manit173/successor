package com.successor.engine.bench;

import com.successor.engine.algo.BipartiteMatcher;
import com.successor.engine.algo.BipartiteMatcher.Edge;
import com.successor.engine.algo.BloomFilter;
import com.successor.engine.algo.Selection;
import com.successor.engine.algo.Work;
import com.successor.engine.data.Dataset;
import com.successor.engine.data.Names;
import com.successor.engine.data.SyntheticData;
import com.successor.engine.model.Player;
import com.successor.engine.scouting.PlayerSearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;

/**
 * Benchmarks: every suite runs the app's algorithm and its baselines on at least three input sizes, reports the
 * median wall time and a machine-independent work count, and checks the answers agree (where the baseline is meant
 * to give the same answer). Inputs are generated from a fixed seed, so runs are reproducible.
 */
public final class Benchmarks {
    private Benchmarks() {
    }

    public record Cell(String variant, boolean baseline, double ms, long work, String answer) {
    }

    /** {@code answersAgree} is null when the variants are not meant to give the same answer (greedy vs optimal). */
    public record Row(String size, long n, Boolean answersAgree, List<Cell> cells) {
    }

    public record Suite(String id, String title, String algorithm, String description, String sizeLabel,
                        String workUnit, List<Row> rows, String environment) {
    }

    public record Info(String id, String title, String algorithm, String description) {
    }

    /** What a variant returns: work done and an answer that comparable variants must agree on. */
    record Outcome(long work, String answer) {
        Outcome withWork(Work w) {
            return new Outcome(w.get(), answer);
        }
    }

    record Variant<I>(String name, boolean baseline, boolean mustAgree, Function<I, Outcome> run) {
    }

    private record Spec<I>(Info info, String sizeLabel, String workUnit, long[] quick, long[] full,
                           Function<Long, I> prepare, List<Variant<I>> variants, Function<Long, String> label) {
    }

    private static final Map<String, Spec<?>> SUITES = new LinkedHashMap<>();

    static {
        SUITES.put("search", searchSuite());
        SUITES.put("topk", topKSuite(10));
        SUITES.put("topk-large", topKSuite(-10));
        SUITES.put("quantiles", quantileSuite());
        SUITES.put("bloom", bloomSuite());
        SUITES.put("matching", matchingSuite());
        SUITES.put("bottleneck", bottleneckSuite());
    }

    public static List<Info> list() {
        return SUITES.values().stream().map(Spec::info).toList();
    }

    public static Suite run(String id, boolean quick) {
        Spec<?> spec = SUITES.get(id);
        if (spec == null) {
            throw new java.util.NoSuchElementException("no benchmark suite " + id + " (have " + SUITES.keySet() + ")");
        }
        return runSpec(spec, quick);
    }

    private static <I> Suite runSpec(Spec<I> spec, boolean quick) {
        List<Row> rows = new ArrayList<>();
        for (long n : quick ? spec.quick() : spec.full()) {
            I input = spec.prepare().apply(n);
            List<Cell> cells = new ArrayList<>();
            String agreed = null;
            boolean agree = true;
            long comparable = spec.variants().stream().filter(Variant::mustAgree).count();
            for (Variant<I> v : spec.variants()) {
                double[] times = new double[quick ? 3 : 5];
                Outcome last = null;
                int runs = 0;
                v.run().apply(input); // warm-up
                for (int r = 0; r < times.length; r++) {
                    long t0 = System.nanoTime();
                    last = v.run().apply(input);
                    times[r] = (System.nanoTime() - t0) / 1e6;
                    runs++;
                    if (times[r] > 2000) {
                        break; // slow baselines: one timed run is enough
                    }
                }
                double[] done = Arrays.copyOf(times, runs);
                Arrays.sort(done);
                cells.add(new Cell(v.name(), v.baseline(), Math.round(done[runs / 2] * 1000) / 1000.0, last.work(),
                        last.answer()));
                if (v.mustAgree()) {
                    if (agreed == null) {
                        agreed = last.answer();
                    } else if (!agreed.equals(last.answer())) {
                        agree = false;
                    }
                }
            }
            rows.add(new Row(spec.label().apply(n), n, comparable >= 2 ? agree : null, cells));
        }
        Info i = spec.info();
        return new Suite(i.id(), i.title(), i.algorithm(), i.description(), spec.sizeLabel(), spec.workUnit(), rows,
                environment());
    }

    public static String environment() {
        return "Java " + System.getProperty("java.version") + ", " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", " + Runtime.getRuntime().availableProcessors() + " cores";
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    // ------------------------------------------------------------------------------------------------ search

    private record SearchInput(PlayerSearch trie, String[][] tokens, List<String> queries) {
    }

    private static Spec<SearchInput> searchSuite() {
        return new Spec<>(new Info("search", "Player search: trie vs linear scan", "Trie (Unit 4)",
                "500 type-ahead queries (2–4 letter prefixes of real name words) over n made-up player names. The "
                        + "trie walks the prefix and visits only the matching subtree; the linear scan checks every "
                        + "word of every name (names pre-normalised, so the baseline is not charged for that). The "
                        + "third column is the app's full search: trie lookup plus ranking all matches and keeping the "
                        + "top 10 with quickselect. Answer = names matched over all queries."),
                "names", "nodes visited / words checked", new long[]{10_000, 50_000, 200_000},
                new long[]{10_000, 100_000, 1_000_000}, n -> {
            List<Player> players = SyntheticData.names((int) (long) n, 7);
            Dataset d = new Dataset(players, List.of());
            String[][] tokens = new String[players.size()][];
            for (int i = 0; i < tokens.length; i++) {
                tokens[i] = Names.tokens(players.get(i).name()).toArray(new String[0]);
            }
            Random rnd = new Random(1);
            List<String> q = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                String[] t = tokens[rnd.nextInt(tokens.length)];
                String w = t[rnd.nextInt(t.length)];
                q.add(w.substring(0, Math.min(w.length(), 2 + rnd.nextInt(3))));
            }
            return new SearchInput(new PlayerSearch(d), tokens, q);
        }, List.of(new Variant<>("Trie lookup", false, true, in -> {
            Work w = Work.counter();
            long matched = 0;
            for (String q : in.queries()) {
                matched += in.trie().trie().prefix(q, w).length;
            }
            return new Outcome(w.get(), Long.toString(matched));
        }), new Variant<>("Trie + ranking (app search)", false, true, in -> {
            long work = 0;
            long matched = 0;
            for (String q : in.queries()) {
                PlayerSearch.Result r = in.trie().search(q, 10);
                work += r.trieWork() + r.rankWork();
                matched += r.matched();
            }
            return new Outcome(work, Long.toString(matched));
        }), new Variant<>("Linear scan", true, true, in -> {
            long work = 0;
            long matched = 0;
            for (String q : in.queries()) {
                for (String[] name : in.tokens()) {
                    for (String t : name) {
                        work++;
                        if (t.startsWith(q)) {
                            matched++;
                            break;
                        }
                    }
                }
            }
            return new Outcome(work, Long.toString(matched));
        })), Benchmarks::fmt);
    }

    // ------------------------------------------------------------------------------------------------ top-k

    private static double[] randomDoubles(long n, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] a = new double[(int) n];
        for (int i = 0; i < a.length; i++) {
            a[i] = rnd.nextDouble() * 4; // like distances
        }
        return a;
    }

    /** k &gt; 0: a fixed k; k &lt; 0: k = n / |k| (a shortlist that grows with the pool). */
    private static Spec<double[]> topKSuite(int kSpec) {
        boolean fixed = kSpec > 0;
        java.util.function.ToIntFunction<double[]> kOf = a -> fixed ? kSpec : Math.max(1, a.length / -kSpec);
        return new Spec<>(new Info(fixed ? "topk" : "topk-large", fixed
                ? "Top 10 of n distances: quickselect vs heap vs sort"
                : "Top 10% of n distances: quickselect vs heap vs sort",
                "Randomized quickselect (Unit 6, CO5)", fixed
                ? "The successor search keeps the 10 closest candidates. Quickselect finds the 10th smallest in "
                        + "expected O(n), then sorts 10; the binary heap keeps the best 10 in O(n log k), and for a "
                        + "small k almost every element is rejected after one comparison with the root, so the heap "
                        + "is the fastest here; sorting everything costs O(n log n)."
                : "A shortlist of the closest 10% (e.g. every candidate worth a scout's look). Now k grows with n: "
                        + "the heap costs O(n log k) with log k large, while quickselect stays O(n) + O(k log k) "
                        + "for sorting the winners."),
                "distances", "comparisons", new long[]{10_000, 100_000, 1_000_000},
                new long[]{100_000, 1_000_000, 5_000_000}, n -> randomDoubles(n, 3), List.of(
                new Variant<>("Quickselect top-k", false, true, a -> {
                    Work w = Work.counter();
                    return new Outcome(0, digest(Selection.topK(a, kOf.applyAsInt(a), new Random(1), w)))
                            .withWork(w);
                }),
                new Variant<>("Binary heap top-k", true, true, a -> {
                    Work w = Work.counter();
                    return new Outcome(0, digest(Selection.heapTopK(a, kOf.applyAsInt(a), w))).withWork(w);
                }),
                new Variant<>("Sort all", true, true, a -> {
                    Work w = Work.counter();
                    return new Outcome(0, digest(Selection.sortTopK(a, kOf.applyAsInt(a), w))).withWork(w);
                })), Benchmarks::fmt);
    }

    /** A short fingerprint of an index list, so long answers can be compared without printing them. */
    private static String digest(int[] idx) {
        return idx.length + " ids #" + Integer.toHexString(Arrays.hashCode(idx));
    }

    // ------------------------------------------------------------------------------------------------ quantiles

    private static Spec<double[]> quantileSuite() {
        return new Spec<>(new Info("quantiles", "Median and quartiles: quickselect vs median-of-medians vs sort",
                "Randomized quickselect (Unit 6, CO5) · median-of-medians (Unit 1)",
                "Every cohort needs the median and quartiles of each stat for robust z-scores. One randomized "
                        + "multi-select places all the needed ranks (expected about 2n comparisons) vs deterministic "
                        + "median-of-medians selections (worst-case O(n), bigger constant) vs one full sort "
                        + "(O(n log n), Java's dual-pivot quicksort on a primitive array, no index indirection)."),
                "values", "comparisons", new long[]{10_000, 100_000, 1_000_000},
                new long[]{100_000, 1_000_000, 10_000_000}, n -> randomDoubles(n, 4), List.of(
                new Variant<>("Quickselect (multi-select)", false, true, a -> {
                    Work w = Work.counter();
                    double[] q = Selection.quantiles(a, new double[]{0.25, 0.5, 0.75}, new Random(2), w);
                    return new Outcome(w.get(), quart(q[0], q[1], q[2]));
                }),
                new Variant<>("Median of medians ×3", true, true, a -> {
                    Work w = Work.counter();
                    double[] q = new double[3];
                    double[] qs = {0.25, 0.5, 0.75};
                    for (int i = 0; i < 3; i++) {
                        q[i] = momQuantile(a, qs[i], w);
                    }
                    return new Outcome(w.get(), quart(q[0], q[1], q[2]));
                }),
                new Variant<>("Sort", true, true, a -> {
                    double[] s = a.clone();
                    Arrays.sort(s);
                    long n = s.length;
                    return new Outcome(Math.round(n * Math.log(n) / Math.log(2)), quart(interp(s, 0.25),
                            interp(s, 0.5), interp(s, 0.75)));
                })), Benchmarks::fmt);
    }

    private static String quart(double a, double b, double c) {
        return String.format(Locale.ROOT, "%.9f/%.9f/%.9f", a, b, c);
    }

    private static double interp(double[] sorted, double q) {
        double h = q * (sorted.length - 1);
        int lo = (int) Math.floor(h);
        return lo + 1 < sorted.length ? sorted[lo] + (h - lo) * (sorted[lo + 1] - sorted[lo]) : sorted[lo];
    }

    private static double momQuantile(double[] a, double q, Work w) {
        double h = q * (a.length - 1);
        int lo = (int) Math.floor(h);
        int[] idx = identity(a.length);
        double x = a[idx[Selection.medianOfMedians(a, idx, lo, w)]];
        if (h == lo) {
            return x;
        }
        int[] idx2 = identity(a.length);
        double y = a[idx2[Selection.medianOfMedians(a, idx2, lo + 1, w)]];
        return x + (h - lo) * (y - x);
    }

    private static int[] identity(int n) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        return idx;
    }

    // ------------------------------------------------------------------------------------------------ bloom

    private record BloomInput(Set<String> hash, long[] sorted, BloomFilter bloom, String[] queries) {
    }

    private static Spec<BloomInput> bloomSuite() {
        return new Spec<>(new Info("bloom", "Membership pre-check: Bloom filter vs exact index only",
                "Bloom filter (Unit 6, CO5)",
                "n record keys are loaded; n more are offered, half of them new (as when a new data release "
                        + "arrives). A 'definitely new' from the Bloom filter (1% false positives) skips the exact "
                        + "look-up; 'maybe' is always confirmed. Against an in-memory HashSet the pre-check only adds "
                        + "time; against a sorted index searched by bisection (like an on-disk index) it saves "
                        + "look-ups. Work = exact look-ups performed."),
                "keys", "exact look-ups", new long[]{100_000, 300_000, 1_000_000},
                new long[]{100_000, 1_000_000, 5_000_000}, n -> {
            int size = (int) (long) n;
            Set<String> hash = new HashSet<>(size * 2);
            long[] fp = new long[size];
            BloomFilter bloom = BloomFilter.forCapacity(size, 0.01);
            for (int i = 0; i < size; i++) {
                String k = key(i);
                hash.add(k);
                fp[i] = fingerprint(k);
                bloom.add(k);
            }
            Arrays.sort(fp);
            String[] q = new String[size];
            Random rnd = new Random(9);
            for (int i = 0; i < size; i++) {
                q[i] = rnd.nextBoolean() ? key(rnd.nextInt(size)) : key(size + i);
            }
            return new BloomInput(hash, fp, bloom, q);
        }, List.of(
                new Variant<>("HashSet only", true, true, in -> {
                    long found = 0;
                    for (String k : in.queries()) {
                        if (in.hash().contains(k)) {
                            found++;
                        }
                    }
                    return new Outcome(in.queries().length, Long.toString(found));
                }),
                new Variant<>("Bloom + HashSet", false, true, in -> {
                    long found = 0;
                    long exact = 0;
                    for (String k : in.queries()) {
                        if (in.bloom().mightContain(k)) {
                            exact++;
                            if (in.hash().contains(k)) {
                                found++;
                            }
                        }
                    }
                    return new Outcome(exact, Long.toString(found));
                }),
                new Variant<>("Sorted index only", true, true, in -> {
                    long found = 0;
                    for (String k : in.queries()) {
                        if (Arrays.binarySearch(in.sorted(), fingerprint(k)) >= 0) {
                            found++;
                        }
                    }
                    return new Outcome(in.queries().length, Long.toString(found));
                }),
                new Variant<>("Bloom + sorted index", false, true, in -> {
                    long found = 0;
                    long exact = 0;
                    for (String k : in.queries()) {
                        if (in.bloom().mightContain(k)) {
                            exact++;
                            if (Arrays.binarySearch(in.sorted(), fingerprint(k)) >= 0) {
                                found++;
                            }
                        }
                    }
                    return new Outcome(exact, Long.toString(found));
                })), Benchmarks::fmt);
    }

    private static String key(int i) {
        return "UNDERSTAT|" + i + "|" + (2014 + i % 10) + "|Club " + (i % 97);
    }

    private static long fingerprint(String k) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < k.length(); i++) {
            h ^= k.charAt(i);
            h *= 0x100000001B3L;
        }
        return h;
    }

    // ------------------------------------------------------------------------------------------------ matching

    private record Graph(int left, int right, List<Edge> edges) {
    }

    /** Each left vertex gets {@code degree} edges; right vertices drawn from a smaller "popular" pool half the time. */
    private static Graph graph(int left, int right, int degree, long seed) {
        Random rnd = new Random(seed);
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < left; i++) {
            Set<Integer> seen = new HashSet<>();
            for (int d = 0; d < degree; d++) {
                int j = rnd.nextBoolean() ? rnd.nextInt(Math.max(1, right / 4)) : rnd.nextInt(right);
                if (seen.add(j)) {
                    edges.add(new Edge(i, j, Math.round(rnd.nextDouble() * 1000) / 10.0));
                }
            }
        }
        return new Graph(left, right, edges);
    }

    private static Spec<Graph> matchingSuite() {
        return new Spec<>(new Info("matching", "Record linkage: max-flow matching vs greedy",
                "Bipartite matching via Edmonds–Karp max-flow (Unit 5)",
                "Random bipartite graphs shaped like linking rosters (n left, n right, 5 candidate edges each, half "
                        + "aimed at a popular quarter). Maximum matching (O(V·E)) links as many pairs as possible; "
                        + "greedy (best score first) is faster but links fewer. Answer = pairs linked (they differ: "
                        + "that is the point)."),
                "vertices per side", "edge scans", new long[]{200, 1_000, 3_000}, new long[]{500, 2_000, 8_000},
                n -> graph((int) (long) n, (int) (long) n, 5, n), List.of(
                new Variant<>("Max-flow matching", false, false, g -> {
                    BipartiteMatcher.Matching m = BipartiteMatcher.maximum(g.left(), g.right(), g.edges());
                    return new Outcome(m.work(), m.size() + " pairs");
                }),
                new Variant<>("Greedy", true, false, g -> {
                    BipartiteMatcher.Matching m = BipartiteMatcher.greedy(g.left(), g.right(), g.edges());
                    return new Outcome(m.work(), m.size() + " pairs");
                })), Benchmarks::fmt);
    }

    private static Spec<Graph> bottleneckSuite() {
        return new Spec<>(new Info("bottleneck", "Squad planner: bottleneck matching vs greedy",
                "Binary search + bipartite matching (Unit 5)",
                "n departures, each with its 20 best candidates among 10·n players (popular players are near the top "
                        + "for several roles). Bottleneck matching fills every role and maximises the weakest "
                        + "similarity: O(log E) max-flow matchings. Greedy takes the best pair first. Answer = the "
                        + "weakest similarity (and roles left empty)."),
                "departures", "edge scans", new long[]{4, 16, 64}, new long[]{8, 32, 128},
                n -> graph((int) (long) n, (int) (long) n * 10, 20, 31 + n), List.of(
                new Variant<>("Bottleneck matching", false, false, g -> {
                    BipartiteMatcher.Matching m = BipartiteMatcher.bottleneck(g.left(), g.right(), g.edges());
                    return m == null ? new Outcome(0, "infeasible")
                            : new Outcome(m.work(), String.format(Locale.ROOT, "weakest %.1f, 0 empty",
                            m.bottleneck()));
                }),
                new Variant<>("Greedy", true, false, g -> {
                    BipartiteMatcher.Matching m = BipartiteMatcher.greedy(g.left(), g.right(), g.edges());
                    return new Outcome(m.work(), String.format(Locale.ROOT, "weakest %.1f, %d empty",
                            m.bottleneck(), g.left() - m.size()));
                })), Benchmarks::fmt);
    }

    // ------------------------------------------------------------------------------------------------ report

    /** Markdown table for one suite. */
    public static String markdown(Suite s) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(s.title()).append("\n\n").append(s.description()).append("\n\n");
        List<String> variants = s.rows().getFirst().cells().stream().map(Cell::variant).toList();
        sb.append("| ").append(s.sizeLabel());
        for (String v : variants) {
            sb.append(" | ").append(v);
        }
        sb.append(" | answers |\n|---");
        variants.forEach(v -> sb.append("|---"));
        sb.append("|---|\n");
        for (Row r : s.rows()) {
            sb.append("| ").append(r.size());
            for (Cell c : r.cells()) {
                sb.append(String.format(Locale.ROOT, " | %s ms · %s %s", c.ms() < 10 ? String.format(Locale.ROOT,
                        "%.3f", c.ms()) : String.format(Locale.ROOT, "%,.0f", c.ms()), fmt(c.work()), s.workUnit()));
                if (!c.answer().matches("[\\d\\[].*") || c.answer().length() < 40) {
                    sb.append(" · ").append(c.answer().length() > 40 ? c.answer().substring(0, 40) + "…" : c.answer());
                }
            }
            sb.append(" | ").append(r.answersAgree() == null ? "not comparable (optimal vs greedy)"
                    : r.answersAgree() ? "agree" : "**differ**").append(" |\n");
        }
        return sb.append('\n').toString();
    }
}

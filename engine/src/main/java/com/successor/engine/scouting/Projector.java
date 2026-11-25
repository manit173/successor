package com.successor.engine.scouting;

import com.successor.engine.algo.Selection;
import com.successor.engine.algo.Work;
import com.successor.engine.data.Dataset;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Stat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Next-season projections, in robust-z space (so every stat, season and source share one scale).
 *
 * <ul>
 *   <li><b>Marcel</b> (after Tango's baseball system): the last three seasons weighted 5/4/3 and by minutes, shrunk
 *       toward the position median by {@code n / (n + R)} (n = minutes behind the estimate, R fitted on training
 *       seasons), plus the average one-year change for players of that age at that position (an aging curve from
 *       consecutive-season pairs, the "delta method").</li>
 *   <li><b>Comparables</b> (after PECOTA): find the k = 25 player-seasons at the same position and age (±1) whose
 *       profiles were closest (top-k by quickselect), and add their average change into the following season. Their
 *       spread gives an 80% range (10th–90th percentile of their changes, found with quickselect).</li>
 *   <li><b>Blend</b>: the mean of the two (the default).</li>
 * </ul>
 * Baselines: <b>naive</b> (next season = last season) and the plain 3-season mean. {@link #backtest} projects past
 * seasons from earlier data only and measures the error of each method.
 */
public final class Projector {
    public enum Method { NAIVE, MEAN3, MARCEL, COMPARABLES, BLEND }

    private static final double[] YEAR_WEIGHTS = {5, 4, 3};
    public static final int MIN_SEASON_MINUTES = 270;
    public static final int K = 25;
    public static final int MIN_COMPARABLES = 8;
    public static final double[] REGRESSION_GRID = {300, 600, 1000, 1500, 2500, 4000};

    private final Dataset data;
    private final Cohorts cohorts;
    private final int minMinutes;
    private volatile double regression = 1500;
    private volatile double rangeScale = 1.0;
    private volatile Method defaultMethod = Method.MARCEL;
    private final Map<String, Index> indexes = new HashMap<>();

    public Projector(Dataset data, Cohorts cohorts) {
        this.data = data;
        this.cohorts = cohorts;
        this.minMinutes = cohorts.minMinutes();
    }

    public double regressionMinutes() {
        return regression;
    }

    /** The method with the lowest error on the training seasons of the last backtest (Marcel until then). */
    public Method defaultMethod() {
        return defaultMethod;
    }

    /** Factor applied to the comparables' spread so the 80% range covers 80% on the training seasons. */
    public double rangeScale() {
        return rangeScale;
    }

    /** Applies a saved calibration (see {@code DatasetBuilder}, {@code calibration.properties}). */
    public void calibrate(double regressionMinutes, double rangeScale, Method method) {
        setRegressionMinutes(regressionMinutes);
        if (!(rangeScale > 0)) {
            throw new IllegalArgumentException("range scale must be > 0");
        }
        this.rangeScale = rangeScale;
        this.defaultMethod = method;
    }

    public void setRegressionMinutes(double r) {
        if (!(r > 0)) {
            throw new IllegalArgumentException("regression minutes must be > 0");
        }
        this.regression = r;
    }

    /** A projection for season {@code season}, as z-scores against {@code position}'s cohort. */
    public record Projection(String playerId, Position position, int season, int age, double[] z, double[] low,
                             double[] high, double[] last, List<Integer> basedOn, int comparables,
                             List<String> comparableIds, Method method) {
    }

    /** A consecutive-season pair at one position: the raw material of aging curves and comparables. */
    record Pair(String playerId, int season, int age, double[] z, double[] next) {
    }

    private static final class Index {
        final List<Pair> pairs = new ArrayList<>();
        final double[][] aging = new double[50][]; // [age][stat]
    }

    // ------------------------------------------------------------------------------------------------ project

    /**
     * Projects season {@code cutoff + 1} for a player as a {@code pos}, using only seasons up to {@code cutoff}.
     * Returns null when he has no season of at least {@value #MIN_SEASON_MINUTES} minutes in the last three.
     */
    public Projection project(String playerId, Position pos, int cutoff, Method method) {
        Player p = data.player(playerId);
        Map<Integer, double[]> zBySeason = new TreeMap<>();
        Map<Integer, Double> minutesBySeason = new HashMap<>();
        seasonVectors(p, pos, cutoff - 2, cutoff, zBySeason, minutesBySeason);
        if (zBySeason.isEmpty()) {
            return null;
        }
        int age = p.ageAtSeason(cutoff + 1);
        double[] base = new double[Stat.COUNT];
        double[] mean3 = new double[Stat.COUNT];
        double[] n = new double[Stat.COUNT];
        double[] last = new double[Stat.COUNT];
        Arrays.fill(last, Double.NaN);
        double[] wSum = new double[Stat.COUNT];
        double[] mSum = new double[Stat.COUNT];
        for (Map.Entry<Integer, double[]> e : zBySeason.entrySet()) {
            double yw = YEAR_WEIGHTS[cutoff - e.getKey()];
            double m = minutesBySeason.get(e.getKey());
            double[] z = e.getValue();
            for (int i = 0; i < Stat.COUNT; i++) {
                if (!Double.isNaN(z[i])) {
                    base[i] += yw * m * z[i];
                    wSum[i] += yw * m;
                    mean3[i] += m * z[i];
                    mSum[i] += m;
                    n[i] += m;
                    last[i] = z[i]; // seasons are visited oldest first, so the latest wins
                }
            }
        }
        for (int i = 0; i < Stat.COUNT; i++) {
            base[i] = wSum[i] > 0 ? base[i] / wSum[i] : Double.NaN;
            mean3[i] = mSum[i] > 0 ? mean3[i] / mSum[i] : Double.NaN;
        }

        Index idx = index(pos, cutoff);
        double[] marcel = new double[Stat.COUNT];
        double[] aging = agingAt(idx, age - 1);
        for (int i = 0; i < Stat.COUNT; i++) {
            marcel[i] = Double.isNaN(base[i]) ? Double.NaN
                    : base[i] * n[i] / (n[i] + regression) + (aging == null || Double.isNaN(aging[i]) ? 0 : aging[i]);
        }

        // comparables: nearest pairs at the same age (±1), by distance between profiles
        List<Pair> pool = new ArrayList<>();
        List<Double> dist = new ArrayList<>();
        for (Pair pr : idx.pairs) {
            if (pr.playerId.equals(playerId) || Math.abs(pr.age - (age - 1)) > 1) {
                continue;
            }
            double d = distance(base, pr.z);
            if (!Double.isNaN(d)) {
                pool.add(pr);
                dist.add(d);
            }
        }
        double[] keys = dist.stream().mapToDouble(Double::doubleValue).toArray();
        int[] near = Selection.topK(keys, K, new Random(playerId.hashCode() * 31L + cutoff), Work.counter());
        double[] comp = new double[Stat.COUNT];
        double[] low = new double[Stat.COUNT];
        double[] high = new double[Stat.COUNT];
        Arrays.fill(comp, Double.NaN);
        Arrays.fill(low, Double.NaN);
        Arrays.fill(high, Double.NaN);
        Random rnd = new Random(7);
        for (int i = 0; i < Stat.COUNT; i++) {
            if (Double.isNaN(base[i])) {
                continue;
            }
            double[] deltas = new double[near.length];
            int c = 0;
            for (int j : near) {
                Pair pr = pool.get(j);
                if (!Double.isNaN(pr.z[i]) && !Double.isNaN(pr.next[i])) {
                    deltas[c++] = pr.next[i] - pr.z[i];
                }
            }
            if (c < MIN_COMPARABLES) {
                continue;
            }
            double[] ds = Arrays.copyOf(deltas, c);
            double mean = Arrays.stream(ds).average().orElse(0);
            comp[i] = base[i] + mean;
            double q10 = rangeScale * (Selection.quantile(ds, 0.1, rnd, Work.counter()) - mean);
            double q90 = rangeScale * (Selection.quantile(ds, 0.9, rnd, Work.counter()) - mean);
            low[i] = q10;
            high[i] = q90;
        }

        double[] out = new double[Stat.COUNT];
        for (int i = 0; i < Stat.COUNT; i++) {
            out[i] = switch (method) {
                case NAIVE -> last[i];
                case MEAN3 -> mean3[i];
                case MARCEL -> marcel[i];
                case COMPARABLES -> Double.isNaN(comp[i]) ? marcel[i] : comp[i];
                case BLEND -> Double.isNaN(comp[i]) ? marcel[i] : (marcel[i] + comp[i]) / 2;
            };
            // the range is the comparables' spread around whichever centre the method chose
            low[i] = Double.isNaN(low[i]) || Double.isNaN(out[i]) ? Double.NaN : out[i] + low[i];
            high[i] = Double.isNaN(high[i]) || Double.isNaN(out[i]) ? Double.NaN : out[i] + high[i];
        }
        List<String> compIds = new ArrayList<>();
        for (int j : near) {
            compIds.add(pool.get(j).playerId + "@" + pool.get(j).season);
        }
        return new Projection(playerId, pos, cutoff + 1, age, out, low, high, last,
                List.copyOf(zBySeason.keySet()), near.length, compIds, method);
    }

    /** Per-season z-vectors (stints in one season combined by minutes) for seasons from..to with enough minutes. */
    private void seasonVectors(Player p, Position pos, int from, int to, Map<Integer, double[]> zOut,
                               Map<Integer, Double> minutesOut) {
        Map<Integer, double[]> sum = new TreeMap<>();
        Map<Integer, double[]> w = new HashMap<>();
        for (PlayerSeason s : data.seasonsOf(p.id())) {
            if (s.season() < from || s.season() > to || s.minutes() < MIN_SEASON_MINUTES) {
                continue;
            }
            double[] z = cohorts.zVector(s, pos);
            double[] acc = sum.computeIfAbsent(s.season(), k -> new double[Stat.COUNT]);
            double[] ww = w.computeIfAbsent(s.season(), k -> new double[Stat.COUNT]);
            for (int i = 0; i < Stat.COUNT; i++) {
                if (!Double.isNaN(z[i])) {
                    acc[i] += s.minutes() * z[i];
                    ww[i] += s.minutes();
                }
            }
            minutesOut.merge(s.season(), s.minutes(), Double::sum);
        }
        for (Map.Entry<Integer, double[]> e : sum.entrySet()) {
            double[] z = new double[Stat.COUNT];
            double[] ww = w.get(e.getKey());
            for (int i = 0; i < Stat.COUNT; i++) {
                z[i] = ww[i] > 0 ? e.getValue()[i] / ww[i] : Double.NaN;
            }
            zOut.put(e.getKey(), z);
        }
    }

    /** Mean distance over shared stats; NaN if fewer than 3 shared. */
    private static double distance(double[] a, double[] b) {
        double s = 0;
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) {
                double d = Math.max(-4, Math.min(4, a[i])) - Math.max(-4, Math.min(4, b[i]));
                s += d * d;
                n++;
            }
        }
        return n < 3 ? Double.NaN : Math.sqrt(s / n);
    }

    // ------------------------------------------------------------------------------------------------ index

    /** Pairs (t, t+1) at {@code pos} with t+1 ≤ cutoff, and the aging curve from them (no data after cutoff). */
    private synchronized Index index(Position pos, int cutoff) {
        return indexes.computeIfAbsent(pos.name() + "|" + cutoff, k -> buildIndex(pos, cutoff));
    }

    private Index buildIndex(Position pos, int cutoff) {
        Index idx = new Index();
        for (Player p : data.players()) {
            Map<Integer, PlayerSeason> main = new TreeMap<>();
            for (PlayerSeason s : data.seasonsOf(p.id())) {
                // Big-5 seasons only: a move from a smaller league would otherwise look like aging
                if (s.season() > cutoff || s.minutes() < minMinutes || !s.league().big5()) {
                    continue;
                }
                PlayerSeason cur = main.get(s.season());
                if (cur == null || s.minutes() > cur.minutes()) {
                    main.put(s.season(), s);
                }
            }
            for (Map.Entry<Integer, PlayerSeason> e : main.entrySet()) {
                PlayerSeason a = e.getValue();
                PlayerSeason b = main.get(e.getKey() + 1);
                Position at = a.position() != null ? a.position() : p.position();
                if (b == null || at != pos) {
                    continue;
                }
                int age = p.ageAtSeason(a.season());
                if (age < 15 || age > 45) {
                    continue;
                }
                idx.pairs.add(new Pair(p.id(), a.season(), age, cohorts.zVector(a, pos), cohorts.zVector(b, pos)));
            }
        }
        // aging curve: mean change at each age, pooled over age ±1 (±2 when thin)
        for (int age = 15; age < 45; age++) {
            double[] curve = new double[Stat.COUNT];
            Arrays.fill(curve, Double.NaN);
            for (int i = 0; i < Stat.COUNT; i++) {
                for (int width = 1; width <= 2 && Double.isNaN(curve[i]); width++) {
                    double sum = 0;
                    int c = 0;
                    for (Pair pr : idx.pairs) {
                        if (Math.abs(pr.age - age) <= width && !Double.isNaN(pr.z[i]) && !Double.isNaN(pr.next[i])) {
                            sum += pr.next[i] - pr.z[i];
                            c++;
                        }
                    }
                    if (c >= 15) {
                        curve[i] = sum / c;
                    }
                }
            }
            idx.aging[age] = curve;
        }
        return idx;
    }

    private static double[] agingAt(Index idx, int age) {
        if (age < 15 || age >= idx.aging.length) {
            return null;
        }
        return idx.aging[age];
    }

    /** The aging curve for one stat at a position (mean change in z from age a to a+1), using all data. */
    public Map<Integer, Double> agingCurve(Position pos, Stat stat) {
        int last = data.seasons().stream().mapToInt(PlayerSeason::season).max().orElse(0);
        Index idx = index(pos, last);
        Map<Integer, Double> out = new LinkedHashMap<>();
        for (int age = 17; age <= 36; age++) {
            double v = idx.aging[age] == null ? Double.NaN : idx.aging[age][stat.ordinal()];
            if (!Double.isNaN(v)) {
                out.put(age, Math.round(v * 1000) / 1000.0);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ backtest

    public record MethodScore(Method method, double mae, double improvementVsNaive, double coverage80,
                              long predictions) {
    }

    public record SeasonScore(int season, String label, int players, List<MethodScore> methods) {
    }

    public record Backtest(double regressionMinutes, double rangeScale, Method chosenMethod,
                           List<Integer> trainSeasons, List<MethodScore> training, List<Integer> testSeasons,
                           List<SeasonScore> seasons, List<MethodScore> overall, String note, LeagueCheck leagueCheck) {
        public Backtest withLeagueCheck(LeagueCheck c) {
            return new Backtest(regressionMinutes, rangeScale, chosenMethod, trainSeasons, training, testSeasons,
                    seasons, overall, note, c);
        }
    }

    /**
     * The same test seasons projected with and without the league-strength factors (estimated from transfers up to
     * {@code factorsFrom}), overall and for the players arriving from a league outside the Big 5.
     */
    public record LeagueCheck(String method, String factorsFrom, int arrivals, double arrivalsMae,
                              double arrivalsMaeUnadjusted, double overallMae, double overallMaeUnadjusted,
                              String note) {
    }

    /** {abs error sum, stat count, players} over test seasons; {@code arrivalsOnly} keeps players new to the Big 5. */
    public double[] errors(List<Integer> test, Method m, boolean arrivalsOnly) {
        double[] out = new double[3];
        for (int t : test) {
            double[] e = score(t, m, arrivalsOnly);
            out[0] += e[0];
            out[1] += e[1];
            out[2] += e[3];
        }
        return out;
    }

    /**
     * Fits R on {@code train} target seasons (Marcel error), then projects every eligible player-season in
     * {@code test} from the seasons before it, for every method. Error = mean absolute error in z units over all
     * position stats measured in both the projection and the outcome. Coverage = share of outcomes inside the 80%
     * range (should be near 80%).
     */
    public Backtest backtest(List<Integer> train, List<Integer> test) {
        double bestR = regression;
        double bestErr = Double.POSITIVE_INFINITY;
        for (double r : REGRESSION_GRID) {
            setRegressionMinutes(r);
            double err = 0;
            long n = 0;
            for (int t : train) {
                double[] e = score(t, Method.MARCEL);
                err += e[0];
                n += (long) e[1];
            }
            if (n > 0 && err / n < bestErr) {
                bestErr = err / n;
                bestR = r;
            }
        }
        setRegressionMinutes(bestR);

        // range width: the factor whose training coverage is closest to 80%
        double bestScale = 1;
        double bestGap = Double.POSITIVE_INFINITY;
        for (double f : new double[]{1.0, 1.1, 1.2, 1.3, 1.4, 1.5}) {
            rangeScale = f;
            double in = 0;
            double all = 0;
            for (int t : train) {
                double[] e = score(t, Method.MARCEL);
                in += e[2];
                all += e[4];
            }
            double gap = all == 0 ? Double.POSITIVE_INFINITY : Math.abs(100 * in / all - 80);
            if (gap < bestGap) {
                bestGap = gap;
                bestScale = f;
            }
        }
        rangeScale = bestScale;

        // default method: lowest training error (the test seasons never influence any choice)
        List<MethodScore> training = new ArrayList<>();
        Method best = Method.MARCEL;
        double bestTrain = Double.POSITIVE_INFINITY;
        double naiveTrain = Double.NaN;
        for (Method m : Method.values()) {
            double err = 0;
            double n = 0;
            for (int t : train) {
                double[] e = score(t, m);
                err += e[0];
                n += e[1];
            }
            double mae = err / n;
            if (m == Method.NAIVE) {
                naiveTrain = mae;
            }
            training.add(new MethodScore(m, round3(mae), round1(100 * (naiveTrain - mae) / naiveTrain), Double.NaN,
                    (long) n));
            if (mae < bestTrain) {
                bestTrain = mae;
                best = m;
            }
        }
        defaultMethod = best;

        List<SeasonScore> seasons = new ArrayList<>();
        Map<Method, double[]> total = new LinkedHashMap<>();
        for (int t : test) {
            List<MethodScore> ms = new ArrayList<>();
            int players = 0;
            double naive = Double.NaN;
            for (Method m : Method.values()) {
                double[] e = score(t, m);
                players = (int) e[3];
                double mae = e[1] == 0 ? Double.NaN : e[0] / e[1];
                if (m == Method.NAIVE) {
                    naive = mae;
                }
                ms.add(new MethodScore(m, round3(mae), round1(100 * (naive - mae) / naive),
                        e[4] == 0 ? Double.NaN : round1(100 * e[2] / e[4]), (long) e[1]));
                double[] tot = total.computeIfAbsent(m, k -> new double[4]);
                tot[0] += e[0];
                tot[1] += e[1];
                tot[2] += e[2];
                tot[3] += e[4];
            }
            seasons.add(new SeasonScore(t, PlayerSeason.label(t), players, ms));
        }
        List<MethodScore> overall = new ArrayList<>();
        double naive = total.get(Method.NAIVE)[0] / total.get(Method.NAIVE)[1];
        for (Map.Entry<Method, double[]> e : total.entrySet()) {
            double[] v = e.getValue();
            double mae = v[0] / v[1];
            overall.add(new MethodScore(e.getKey(), round3(mae), round1(100 * (naive - mae) / naive),
                    v[3] == 0 ? Double.NaN : round1(100 * v[2] / v[3]), (long) v[1]));
        }
        return new Backtest(bestR, rangeScale, defaultMethod, train, training, test, seasons, overall,
                "Errors are mean absolute differences in robust z units (1 ≈ one standard deviation within the "
                        + "position-season). R, the range width and the default method were chosen on the training "
                        + "seasons only; the test seasons are projected from earlier data only.", null);
    }

    /** {abs error sum, stat count, inside-range count, players, range count} for projecting season t. */
    private double[] score(int t, Method m) {
        return score(t, m, false);
    }

    private double[] score(int t, Method m, boolean arrivalsOnly) {
        double err = 0;
        double n = 0;
        double inside = 0;
        double ranged = 0;
        int players = 0;
        for (Player p : data.players()) {
            PlayerSeason actual = null;
            for (PlayerSeason s : data.seasonsOf(p.id())) {
                if (s.season() == t && s.minutes() >= minMinutes && s.league().big5()
                        && (actual == null || s.minutes() > actual.minutes())) {
                    actual = s; // the backtest scores Big-5 seasons, the population the yardstick describes
                }
            }
            if (actual == null) {
                continue;
            }
            if (arrivalsOnly) {
                PlayerSeason before = null;
                for (PlayerSeason s : data.seasonsOf(p.id())) {
                    if (s.season() == t - 1 && (before == null || s.minutes() > before.minutes())) {
                        before = s;
                    }
                }
                if (before == null || before.league().big5()) {
                    continue; // his previous season was in the Big 5 (or there was none)
                }
            }
            Position pos = actual.position() != null ? actual.position() : p.position();
            if (pos == null) {
                continue;
            }
            Projection pr = project(p.id(), pos, t - 1, m);
            if (pr == null) {
                continue;
            }
            double[] z = cohorts.zVector(actual, pos);
            boolean any = false;
            for (int i = 0; i < Stat.COUNT; i++) {
                if (Double.isNaN(z[i]) || Double.isNaN(pr.z()[i])) {
                    continue;
                }
                double clipped = Math.max(-4, Math.min(4, z[i]));
                err += Math.abs(Math.max(-4, Math.min(4, pr.z()[i])) - clipped);
                n++;
                any = true;
                if (!Double.isNaN(pr.low()[i])) {
                    ranged++;
                    if (z[i] >= pr.low()[i] && z[i] <= pr.high()[i]) {
                        inside++;
                    }
                }
            }
            if (any) {
                players++;
            }
        }
        return new double[]{err, n, inside, players, ranged};
    }

    private static double round1(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 10) / 10.0; // NaN = not measured, never 0
    }

    private static double round3(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 1000) / 1000.0; // NaN = not measured, never 0
    }
}

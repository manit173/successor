package com.successor.engine.scouting;

import com.successor.engine.data.Dataset;
import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Stat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * League-strength factors: what a per-90 number from a league outside the Big 5 is worth in the Big 5, per stat.
 *
 * <p><b>Measured from transfers.</b> For every player whose main season s (at least {@code minMinutes}) was in league
 * L and whose main season s + 1 was in the Big 5, compare the two seasons' per-90 values. For stat i,
 * <pre>  R(L, i) = Σ after / Σ before   (summed over those players)</pre>
 * and the same ratio R(B, i) over players who moved between two Big-5 clubs, which absorbs whatever any transfer does
 * (new team, regression of a player bought after a good season). The raw factor is R(L, i) / R(B, i).
 *
 * <p><b>Shrunk where the sample is small</b> (empirical Bayes on the log scale): leagues are treated as draws from
 * one population of non-Big-5 leagues, so each log-factor is pulled toward the precision-weighted mean of the
 * well-measured leagues μ(i) (DerSimonian–Laird), by the weight τ² / (τ² + v), where v is the factor's sampling variance (a bootstrap over the league's
 * movers) and τ² the spread of that stat's true factors between leagues (moments: observed spread minus sampling
 * noise). A
 * league with hundreds of movers keeps its own number; one with a handful gets roughly the typical league's.
 *
 * <p>Attacking output typically shrinks after such a move (Eredivisie goals ×0.69), defending does not (tackles
 * ×1.0, blocks ×1.2), which is why there is one factor per stat rather than one per league.
 */
public final class LeagueStrength {
    /** Leagues with fewer movers than this do not inform μ(i) or τ². */
    static final int MIN_FOR_PRIOR = 20;
    /** Below this many movers with the stat measured, a league's own estimate is ignored (the prior is used). */
    static final int MIN_OWN = 5;
    private static final int BOOTSTRAP = 200;
    /** Smallest between-league variance of a log-factor (a 4.5% spread): never assume leagues are identical. */
    static final double TAU2_FLOOR = 0.002;

    /** One league-stat factor with the evidence behind it. */
    public record Estimate(League league, Stat stat, int movers, double raw, double factor) {
    }

    private final Map<League, double[]> factors;
    private final List<Estimate> estimates;
    private final int lastTargetSeason;

    private LeagueStrength(Map<League, double[]> factors, List<Estimate> estimates, int lastTargetSeason) {
        this.factors = factors;
        this.estimates = estimates;
        this.lastTargetSeason = lastTargetSeason;
    }

    /** No adjustment: every factor is 1. */
    public static LeagueStrength none() {
        return new LeagueStrength(new EnumMap<>(League.class), List.of(), -1);
    }

    /** Multiplies a per-90 value from {@code league} into its Big-5 equivalent (1 for Big-5 leagues). */
    public double factor(League league, Stat stat) {
        double[] f = factors.get(league);
        return f == null ? 1 : f[stat.ordinal()];
    }

    public List<Estimate> estimates() {
        return estimates;
    }

    /** The latest Big-5 season whose arrivals informed the factors (−1 for {@link #none()}). */
    public int lastTargetSeason() {
        return lastTargetSeason;
    }

    /** A short summary for notes, e.g. "goals ×0.69, assists ×0.64, tackles ×1.02". */
    public String headline(League league) {
        if (league.big5() || !factors.containsKey(league)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Stat s : new Stat[]{Stat.NP_GOALS, Stat.ASSISTS, Stat.TACKLES}) {
            double f = factor(league, s);
            if (f != 1) {
                parts.add(s.label().toLowerCase(Locale.ROOT).replace("non-penalty ", "") + " ×"
                        + String.format(Locale.ROOT, "%.2f", f));
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    // ------------------------------------------------------------------------------------------------ estimation

    private record Move(double[] before, double[] after) {
    }

    /** Estimates the factors from every transfer in the data. */
    public static LeagueStrength estimateAll(Dataset data, int minMinutes) {
        int last = data.seasons().stream().mapToInt(PlayerSeason::season).max().orElse(-1);
        return estimate(data, minMinutes, last);
    }

    /**
     * Estimates the factors from transfers whose Big-5 season is at most {@code lastTargetSeason} (so a backtest can
     * use only seasons before its test seasons).
     */
    public static LeagueStrength estimate(Dataset data, int minMinutes, int lastTargetSeason) {
        Map<League, List<Move>> moves = new EnumMap<>(League.class);
        List<Move> baseline = new ArrayList<>();
        for (Player p : data.players()) {
            Map<Integer, PlayerSeason> main = new HashMap<>();
            for (PlayerSeason s : data.seasonsOf(p.id())) {
                if (s.minutes() >= minMinutes) {
                    main.merge(s.season(), s, (a, b) -> a.minutes() >= b.minutes() ? a : b);
                }
            }
            for (PlayerSeason a : main.values()) {
                PlayerSeason b = main.get(a.season() + 1);
                if (b == null || !b.league().big5() || b.season() > lastTargetSeason) {
                    continue;
                }
                Move m = new Move(per90(a), per90(b));
                if (!a.league().big5()) {
                    moves.computeIfAbsent(a.league(), k -> new ArrayList<>()).add(m);
                } else if (!a.club().equals(b.club())) {
                    baseline.add(m);
                }
            }
        }
        double[] base = new double[Stat.COUNT];
        for (int i = 0; i < Stat.COUNT; i++) {
            base[i] = ratio(baseline, i)[0];
        }

        // raw log-factors and their bootstrap variances
        Map<League, double[]> x = new EnumMap<>(League.class);
        Map<League, double[]> v = new EnumMap<>(League.class);
        Map<League, int[]> n = new EnumMap<>(League.class);
        for (Map.Entry<League, List<Move>> e : moves.entrySet()) {
            List<Move> ms = e.getValue();
            double[] xs = new double[Stat.COUNT];
            double[] vs = new double[Stat.COUNT];
            int[] ns = new int[Stat.COUNT];
            Arrays.fill(xs, Double.NaN);
            Arrays.fill(vs, Double.NaN);
            Random rnd = new Random(1000L + e.getKey().ordinal());
            for (int i = 0; i < Stat.COUNT; i++) {
                double[] r = ratio(ms, i);
                ns[i] = (int) r[1];
                if (ns[i] < MIN_OWN || !(r[0] > 0) || !(base[i] > 0)) {
                    continue;
                }
                xs[i] = Math.log(r[0] / base[i]);
                double sum = 0;
                double sumSq = 0;
                int k = 0;
                List<Move> sample = new ArrayList<>(ms.size());
                for (int b = 0; b < BOOTSTRAP; b++) {
                    sample.clear();
                    for (int j = 0; j < ms.size(); j++) {
                        sample.add(ms.get(rnd.nextInt(ms.size())));
                    }
                    double rb = ratio(sample, i)[0];
                    if (rb > 0) {
                        double lx = Math.log(rb / base[i]);
                        sum += lx;
                        sumSq += lx * lx;
                        k++;
                    }
                }
                vs[i] = k > 1 ? Math.max(1e-6, (sumSq - sum * sum / k) / (k - 1)) : Double.NaN;
                if (Double.isNaN(vs[i])) {
                    xs[i] = Double.NaN;
                }
            }
            x.put(e.getKey(), xs);
            v.put(e.getKey(), vs);
            n.put(e.getKey(), ns);
        }

        // prior per stat, by the DerSimonian–Laird random-effects estimator of meta-analysis over the leagues with
        // at least MIN_FOR_PRIOR movers: w = 1 / v, μ(i) = Σ w·x / Σ w, Q = Σ w·(x − μ)²,
        // τ²(i) = max(floor, (Q − (k − 1)) / (Σ w − Σ w² / Σ w)), the spread of the true factors between leagues
        // beyond what sampling noise explains. Precise leagues count more than noisy ones. A stat measured in fewer
        // than three leagues borrows the median τ² of the others.
        double[] mu = new double[Stat.COUNT];
        double[] tau2 = new double[Stat.COUNT];
        Arrays.fill(mu, Double.NaN);
        Arrays.fill(tau2, Double.NaN);
        List<Double> measuredTau2 = new ArrayList<>();
        for (int i = 0; i < Stat.COUNT; i++) {
            double sw = 0;
            double swx = 0;
            double sw2 = 0;
            int k = 0;
            for (League l : x.keySet()) {
                if (n.get(l)[i] >= MIN_FOR_PRIOR && !Double.isNaN(x.get(l)[i])) {
                    double w = 1 / v.get(l)[i];
                    sw += w;
                    swx += w * x.get(l)[i];
                    sw2 += w * w;
                    k++;
                }
            }
            if (k == 0) {
                continue;
            }
            mu[i] = swx / sw;
            if (k >= 3) {
                double q = 0;
                for (League l : x.keySet()) {
                    if (n.get(l)[i] >= MIN_FOR_PRIOR && !Double.isNaN(x.get(l)[i])) {
                        double d = x.get(l)[i] - mu[i];
                        q += d * d / v.get(l)[i];
                    }
                }
                tau2[i] = Math.max(TAU2_FLOOR, (q - (k - 1)) / (sw - sw2 / sw));
                measuredTau2.add(tau2[i]);
            }
        }
        measuredTau2.sort(null);
        double pooled = measuredTau2.isEmpty() ? TAU2_FLOOR : measuredTau2.get(measuredTau2.size() / 2);
        for (int i = 0; i < Stat.COUNT; i++) {
            if (!Double.isNaN(mu[i]) && Double.isNaN(tau2[i])) {
                tau2[i] = pooled;
            }
        }

        Map<League, double[]> factors = new EnumMap<>(League.class);
        List<Estimate> estimates = new ArrayList<>();
        for (League l : League.values()) {
            if (l.big5()) {
                continue;
            }
            double[] f = new double[Stat.COUNT];
            Arrays.fill(f, 1);
            double[] xs = x.get(l);
            for (Stat st : Stat.values()) {
                int i = st.ordinal();
                if (!st.per90() && st != Stat.CLEAN_SHEETS || Double.isNaN(mu[i])) {
                    continue;
                }
                double post = mu[i];
                int movers = n.containsKey(l) ? n.get(l)[i] : 0;
                double raw = Double.NaN;
                if (xs != null && !Double.isNaN(xs[i])) {
                    double vi = v.get(l)[i];
                    post = mu[i] + (xs[i] - mu[i]) * tau2[i] / (tau2[i] + vi);
                    raw = Math.exp(xs[i]);
                }
                f[i] = Math.exp(post);
                estimates.add(new Estimate(l, st, movers, raw, f[i]));
            }
            factors.put(l, f);
        }
        return new LeagueStrength(factors, List.copyOf(estimates), lastTargetSeason);
    }

    private static double[] per90(PlayerSeason s) {
        double[] out = new double[Stat.COUNT];
        for (Stat st : Stat.values()) {
            out[st.ordinal()] = Cohorts.per90(s, st);
        }
        return out;
    }

    /** {Σ after / Σ before, pairs with the stat measured in both seasons}. */
    private static double[] ratio(List<Move> ms, int i) {
        double sb = 0;
        double sa = 0;
        int c = 0;
        for (Move m : ms) {
            double b = m.before[i];
            double a = m.after[i];
            if (!Double.isNaN(b) && !Double.isNaN(a)) {
                sb += b;
                sa += a;
                c++;
            }
        }
        return new double[]{sb > 0 ? sa / sb : Double.NaN, c};
    }

    // ------------------------------------------------------------------------------------------------ storage

    /** As properties: one line per league and stat ({@code EREDIVISIE.np_goals=0.6912}). */
    public String toProperties() {
        StringBuilder sb = new StringBuilder("# league-strength factors from transfers into the Big 5 up to "
                + PlayerSeason.label(lastTargetSeason) + " (see LeagueStrength)\nlastTargetSeason=" + lastTargetSeason
                + "\n");
        for (Map.Entry<League, double[]> e : factors.entrySet()) {
            for (Stat st : Stat.values()) {
                double f = e.getValue()[st.ordinal()];
                if (f != 1) {
                    sb.append(e.getKey().name()).append('.').append(st.key()).append('=')
                            .append(String.format(Locale.ROOT, "%.4f", f)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    public static LeagueStrength fromProperties(java.util.Properties p) {
        Map<League, double[]> factors = new EnumMap<>(League.class);
        for (String key : p.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot < 0) {
                continue;
            }
            League l = League.valueOf(key.substring(0, dot));
            Stat st = Stat.fromKey(key.substring(dot + 1));
            double f = Double.parseDouble(p.getProperty(key));
            if (st == null || !(f > 0) || l.big5()) {
                throw new IllegalArgumentException("bad league-strength entry " + key + "=" + p.getProperty(key));
            }
            factors.computeIfAbsent(l, k -> {
                double[] a = new double[Stat.COUNT];
                Arrays.fill(a, 1);
                return a;
            })[st.ordinal()] = f;
        }
        return new LeagueStrength(factors, List.of(), Integer.parseInt(p.getProperty("lastTargetSeason", "-1")));
    }
}

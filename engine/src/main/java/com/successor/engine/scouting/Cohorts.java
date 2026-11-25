package com.successor.engine.scouting;

import com.successor.engine.algo.Selection;
import com.successor.engine.algo.Work;
import com.successor.engine.data.Dataset;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Stat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Comparison cohorts: all Big-5 player-seasons at one position in one season with at least {@code minMinutes}
 * (players from other leagues are scaled against this Big-5 yardstick after their per-90 values are converted by the
 * measured {@link LeagueStrength} factors). For every
 * stat a cohort keeps the per-90 values and three order statistics found by <b>randomized quickselect</b>: the
 * median and the quartiles. They give a robust z-score
 * <pre>  z = (x − median) / (IQR / 1.349)</pre>
 * (IQR/1.349 equals the standard deviation for normal data but ignores outliers such as penalty takers), which puts
 * every stat, season and source on one scale. Understat and Opta measure xG with different models, but each season
 * is scaled by its own cohort, so their scales never mix.
 *
 * <p>Percentile ranks are counted directly, O(n) per value: no full sort of any cohort is ever needed.
 */
public final class Cohorts {
    /** Below this many measured values a stat is not scaled in that cohort. */
    public static final int MIN_MEASURED = 20;

    private final Dataset data;
    private final int minMinutes;
    private final Map<String, Cohort> cache = new HashMap<>();
    private final Work work = Work.counter();
    private volatile LeagueStrength strength = LeagueStrength.none();

    public Cohorts(Dataset data, int minMinutes) {
        if (minMinutes < 0) {
            throw new IllegalArgumentException("minimum minutes must be ≥ 0, got " + minMinutes);
        }
        this.data = data;
        this.minMinutes = minMinutes;
    }

    public int minMinutes() {
        return minMinutes;
    }

    /** The factors applied to seasons outside the Big 5 (cohorts themselves are Big-5 only, so stay valid). */
    public void setStrength(LeagueStrength s) {
        this.strength = s == null ? LeagueStrength.none() : s;
    }

    public LeagueStrength strength() {
        return strength;
    }

    /** The per-90 value compared with the Big-5 cohort: raw in the Big 5, times the league factor elsewhere. */
    public double value(PlayerSeason s, Stat st) {
        double v = per90(s, st);
        return s.league().big5() || Double.isNaN(v) ? v : v * strength.factor(s.league(), st);
    }

    /** Quickselect comparisons spent building cohorts so far. */
    public long work() {
        return work.get();
    }

    /** One stat inside one cohort. */
    public static final class StatScale {
        final double[] values;
        public final double median;
        public final double q1;
        public final double q3;
        public final double scale;

        StatScale(double[] values, double median, double q1, double q3, double scale) {
            this.values = values;
            this.median = median;
            this.q1 = q1;
            this.q3 = q3;
            this.scale = scale;
        }

        public double z(double x) {
            return (x - median) / scale;
        }

        /** Mid-rank percentile in [0, 100]: share of the cohort below x, counting ties as half. */
        public double percentile(double x) {
            int below = 0;
            int equal = 0;
            for (double v : values) {
                if (v < x) {
                    below++;
                } else if (v == x) {
                    equal++;
                }
            }
            return 100.0 * (below + 0.5 * equal) / values.length;
        }

        public int size() {
            return values.length;
        }
    }

    /** All stats of one position-season. */
    public static final class Cohort {
        public final Position position;
        public final int season;
        public final int players;
        private final StatScale[] stats = new StatScale[Stat.COUNT];

        Cohort(Position position, int season, int players) {
            this.position = position;
            this.season = season;
            this.players = players;
        }

        /** Null when fewer than {@link #MIN_MEASURED} players had the stat measured. */
        public StatScale stat(Stat s) {
            return stats[s.ordinal()];
        }
    }

    /** The cohort for a position and season (built on first use, then cached). */
    public synchronized Cohort cohort(Position p, int season) {
        return cache.computeIfAbsent(p.name() + "|" + season, k -> build(p, season));
    }

    private Cohort build(Position p, int season) {
        List<PlayerSeason> rows = new ArrayList<>();
        for (PlayerSeason s : data.seasons()) {
            // the Big 5 are the yardstick: other leagues are described against them, never mixed into them
            if (s.season() == season && s.position() == p && s.minutes() >= minMinutes && s.league().big5()) {
                rows.add(s);
            }
        }
        Cohort c = new Cohort(p, season, rows.size());
        Random rnd = new Random(31L * season + p.ordinal());
        for (Stat st : Stat.values()) {
            if (!st.per90() && st != Stat.CLEAN_SHEETS) {
                continue;
            }
            double[] v = new double[rows.size()];
            int n = 0;
            for (PlayerSeason s : rows) {
                double x = per90(s, st);
                if (!Double.isNaN(x)) {
                    v[n++] = x;
                }
            }
            if (n < MIN_MEASURED) {
                continue;
            }
            double[] vals = java.util.Arrays.copyOf(v, n);
            double[] q = Selection.quantiles(vals, new double[]{0.25, 0.5, 0.75}, rnd, work); // one multi-select
            double q1 = q[0];
            double median = q[1];
            double q3 = q[2];
            double scale = (q3 - q1) / 1.349;
            if (scale < 1e-9) { // e.g. most centre-backs score 0 goals: fall back to the mean absolute deviation
                double mad = 0;
                for (double x : vals) {
                    mad += Math.abs(x - median);
                }
                scale = 1.2533 * mad / n;
            }
            if (scale < 1e-9) {
                scale = 1; // everyone identical: every z is 0
            }
            c.stats[st.ordinal()] = new StatScale(vals, median, q1, q3, scale);
        }
        return c;
    }

    /**
     * The season as robust z-scores against ({@code pos}, its season): one entry per {@link Stat} (NaN where the stat
     * is missing, not used at {@code pos}, or not scaled in that cohort).
     */
    public double[] zVector(PlayerSeason s, Position pos) {
        double[] z = new double[Stat.COUNT];
        java.util.Arrays.fill(z, Double.NaN);
        Cohort c = cohort(pos, s.season());
        for (Stat st : Roles.positionStats(pos).keySet()) {
            StatScale sc = c.stat(st);
            double v = value(s, st);
            if (sc != null && !Double.isNaN(v)) {
                z[st.ordinal()] = sc.z(v);
            }
        }
        return z;
    }

    /** Per-90 value used for comparisons (clean sheets too, which the dataset stores as a season total). */
    public static double per90(PlayerSeason s, Stat st) {
        if (st == Stat.CLEAN_SHEETS) {
            double v = s.get(st);
            return Double.isNaN(v) || s.minutes() <= 0 ? Double.NaN : v * 90 / s.minutes();
        }
        return s.per90(st);
    }
}

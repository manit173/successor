package com.successor.engine.model;

import java.util.Arrays;
import java.util.Set;

/**
 * One player's season at one club in one league (a player who moved mid-season has one row per club).
 * Stats are season totals indexed by {@link Stat#ordinal()}; NaN means the sources did not measure that stat.
 */
public final class PlayerSeason {
    private final String playerId;
    private final int season;
    private final League league;
    private final String club;
    private final Position position;
    private final Set<Source> sources;
    private final double[] stats;

    public PlayerSeason(String playerId, int season, League league, String club, Position position,
                        Set<Source> sources, double[] stats) {
        if (stats.length != Stat.COUNT) {
            throw new IllegalArgumentException("expected " + Stat.COUNT + " stats, got " + stats.length);
        }
        this.playerId = playerId;
        this.season = season;
        this.league = league;
        this.club = club;
        this.position = position;
        this.sources = Set.copyOf(sources);
        this.stats = stats.clone();
    }

    public static double[] emptyStats() {
        double[] s = new double[Stat.COUNT];
        Arrays.fill(s, Double.NaN);
        return s;
    }

    public String playerId() {
        return playerId;
    }

    /** Start year: 2019 means 2019/20. */
    public int season() {
        return season;
    }

    public String seasonLabel() {
        return label(season);
    }

    public static String label(int season) {
        return season + "/" + String.format("%02d", (season + 1) % 100);
    }

    public League league() {
        return league;
    }

    public String club() {
        return club;
    }

    public Position position() {
        return position;
    }

    public Set<Source> sources() {
        return sources;
    }

    public double get(Stat s) {
        return stats[s.ordinal()];
    }

    public boolean has(Stat s) {
        return !Double.isNaN(stats[s.ordinal()]);
    }

    public double minutes() {
        double m = stats[Stat.MINUTES.ordinal()];
        return Double.isNaN(m) ? 0 : m;
    }

    /** Value per 90 minutes for per-90 stats (raw total otherwise); NaN if missing or no minutes. */
    public double per90(Stat s) {
        double v = stats[s.ordinal()];
        if (Double.isNaN(v) || !s.per90()) {
            return v;
        }
        double m = minutes();
        return m <= 0 ? Double.NaN : v * 90.0 / m;
    }

    public double[] statsCopy() {
        return stats.clone();
    }

    @Override
    public String toString() {
        return playerId + " " + seasonLabel() + " " + club + " (" + league.label() + ") " + (int) minutes() + "'";
    }
}

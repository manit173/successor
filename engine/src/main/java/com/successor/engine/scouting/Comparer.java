package com.successor.engine.scouting;

import com.successor.engine.data.Dataset;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Stat;
import com.successor.engine.scouting.Cohorts.StatScale;
import com.successor.engine.scouting.Roles.Role;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Side-by-side comparison of 2–3 players on the stats that matter for the first player's position and role. Each
 * player is described by his last two seasons with enough minutes, scaled against that position, plus his per-90
 * values and percentiles in his latest such season.
 */
public final class Comparer {
    private final Dataset data;
    private final Scout scout;

    public Comparer(Dataset data, Scout scout) {
        this.data = data;
        this.scout = scout;
    }

    /** {@code season}: the most recent of the player's compared seasons that measured this stat. */
    public record Value(double z, double per90, double percentile, String season) {
    }

    public record Column(String id, String name, String position, String club, String league, int age,
                         List<String> seasons, Map<String, Value> stats, String positionNote, String leagueNote) {
    }

    public record Row(String stat, String label, boolean inRole) {
    }

    public record Result(String position, String role, List<Row> stats, List<Column> players, double[][] similarity) {
    }

    public Result compare(List<String> ids, String position) {
        if (ids == null || ids.size() < 2 || ids.size() > 3) {
            throw new IllegalArgumentException("compare 2 or 3 players");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("the same player is listed twice");
        }
        int current = scout.currentSeason();
        List<Player> players = ids.stream().map(scout::player).toList();
        List<List<PlayerSeason>> recent = new ArrayList<>();
        for (Player p : players) {
            List<PlayerSeason> eligible = data.seasonsOf(p.id()).stream()
                    .filter(s -> s.minutes() >= scout.cohorts().minMinutes()).toList();
            if (eligible.isEmpty()) {
                throw new IllegalStateException(p.name() + " has no season with " + scout.cohorts().minMinutes()
                        + "+ minutes to compare");
            }
            recent.add(eligible.subList(Math.max(0, eligible.size() - 2), eligible.size()));
        }
        Position pos = position != null ? Scout.parsePosition(position)
                : scout.mainPosition(recent.getFirst(), players.getFirst());
        Role role = scout.bestRole(recent.getFirst(), pos);
        Map<Stat, Double> weights = new EnumMap<>(Stat.class);
        Roles.positionStats(pos).forEach((s, w) -> weights.put(s, 0.35 * w));
        role.weights().forEach((s, w) -> weights.put(s, Math.max(0.5, Math.abs(w))));

        List<double[]> profiles = new ArrayList<>();
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            List<PlayerSeason> seasons = recent.get(i);
            double[] z = scout.profile(seasons, pos);
            profiles.add(z);
            PlayerSeason latest = seasons.getLast();
            Map<String, Value> stats = new LinkedHashMap<>();
            for (Stat st : weights.keySet()) {
                // the latest season that measured the stat (2025/26 has basic stats only, 2024/25 the full set)
                for (int k = seasons.size() - 1; k >= 0; k--) {
                    PlayerSeason s = seasons.get(k);
                    double v = Cohorts.per90(s, st);
                    StatScale sc = scout.cohorts().cohort(pos, s.season()).stat(st);
                    if (!Double.isNaN(v) && sc != null) {
                        double adj = scout.cohorts().value(s, st); // percentile after the league-strength factor
                        stats.put(st.key(), new Value(round(z[st.ordinal()]), round(v), round(sc.percentile(adj)),
                                s.seasonLabel()));
                        break;
                    }
                }
            }
            Position own = latest.position() != null ? latest.position() : p.position();
            cols.add(new Column(p.id(), p.name(), own == null ? null : own.name(), latest.club(),
                    latest.league().label(), p.ageAtSeason(current), seasons.stream().map(PlayerSeason::seasonLabel)
                    .toList(), stats, Scout.positionNote(own, pos), Scout.leagueNote(latest.league(), scout.cohorts().strength())));
        }
        double[][] sim = new double[players.size()][players.size()];
        for (int a = 0; a < players.size(); a++) {
            for (int b = 0; b < players.size(); b++) {
                double d = a == b ? 0 : Scout.distance(profiles.get(a), profiles.get(b), weights, role)[0];
                sim[a][b] = Double.isNaN(d) ? Double.NaN : Math.round(1000 * Math.exp(-d * d / 4)) / 10.0;
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Stat st : weights.keySet()) {
            rows.add(new Row(st.key(), st.label(), role.weights().containsKey(st)));
        }
        return new Result(pos.name(), role.name(), rows, cols, sim);
    }

    private static double round(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 1000) / 1000.0;
    }
}

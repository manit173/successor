package com.successor.engine.scouting;

import com.successor.engine.algo.BipartiteMatcher;
import com.successor.engine.algo.BipartiteMatcher.Edge;
import com.successor.engine.algo.BipartiteMatcher.Matching;
import com.successor.engine.algo.Selection;
import com.successor.engine.algo.Work;
import com.successor.engine.data.Dataset;
import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Stat;
import com.successor.engine.scouting.Roles.Role;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Squad planner: several players leave; give each vacated role a <em>different</em> replacement.
 *
 * <ol>
 *   <li>For each departing player: his position, best role and projected next season (what the team loses).</li>
 *   <li>His top-N candidates by similarity (quickselect), as edges departure → candidate weighted by similarity.
 *       The same candidate can be near the top for several departures; he can only be signed once.</li>
 *   <li><b>Bottleneck bipartite matching</b> ({@link BipartiteMatcher#bottleneck}): fill every role while making
 *       the weakest replacement as close as possible (binary search on the threshold + max-flow matching).</li>
 *   <li>Baseline: greedy (best pair first, never revisited), which can leave a role with a much weaker player or
 *       with nobody.</li>
 * </ol>
 */
public final class SquadPlanner {
    private final Dataset data;
    private final Scout scout;

    public SquadPlanner(Dataset data, Scout scout) {
        this.data = data;
        this.scout = scout;
    }

    public record Request(List<String> departures, Integer maxAge, List<String> leagues, Integer minMinutes,
                          Integer candidatesPerRole, List<String> exclude) {
    }

    public record Assignment(String departureId, String departureName, String position, String role,
                             String candidateId, String candidateName, int age, String candidatePosition, String club,
                             String league, long marketValueEur, double similarity, String positionNote,
                             String leagueNote) {
    }

    public record Result(List<Assignment> matching, double weakest, List<Assignment> greedy, double greedyWeakest,
                         int greedyFilled, List<String> unfilled, int candidates, int edges, long matchingWork,
                         String nextSeason, String note) {
    }

    public Result plan(Request q) {
        if (q == null || q.departures() == null || q.departures().isEmpty()) {
            throw new IllegalArgumentException("add at least one departing player");
        }
        if (q.departures().size() > 8) {
            throw new IllegalArgumentException("plan at most 8 departures at once, got " + q.departures().size());
        }
        if (new HashSet<>(q.departures()).size() != q.departures().size()) {
            throw new IllegalArgumentException("the same player is listed twice");
        }
        int perRole = q.candidatesPerRole() == null ? 20 : q.candidatesPerRole();
        if (perRole < 1 || perRole > 100) {
            throw new IllegalArgumentException("candidatesPerRole must be between 1 and 100, got " + perRole);
        }
        int minMinutes = q.minMinutes() == null ? scout.cohorts().minMinutes() : q.minMinutes();
        if (minMinutes < 0 || minMinutes > 4000) {
            throw new IllegalArgumentException("minMinutes must be between 0 and 4000, got " + minMinutes);
        }
        if (q.maxAge() != null && (q.maxAge() < 15 || q.maxAge() > 45)) {
            throw new IllegalArgumentException("maxAge must be between 15 and 45, got " + q.maxAge());
        }
        List<League> leagues = q.leagues() == null || q.leagues().isEmpty() ? List.of(League.values())
                : q.leagues().stream().map(l -> {
                    League x = League.fromLabel(l);
                    if (x == null) {
                        throw new IllegalArgumentException("unknown league " + l);
                    }
                    return x;
                }).toList();
        Set<String> excluded = new HashSet<>(q.departures());
        if (q.exclude() != null) {
            excluded.addAll(q.exclude());
        }
        int current = scout.currentSeason();
        Projector projector = scout.projector();

        List<String> candIds = new ArrayList<>();
        Map<String, Integer> candIndex = new HashMap<>();
        List<Edge> edges = new ArrayList<>();
        List<Player> leaving = new ArrayList<>();
        List<Position> positions = new ArrayList<>();
        List<Role> roles = new ArrayList<>();
        Map<String, double[]> projCache = new HashMap<>();
        Work work = Work.counter();

        for (int d = 0; d < q.departures().size(); d++) {
            Player p = scout.player(q.departures().get(d));
            List<PlayerSeason> recent = data.seasonsOf(p.id()).stream()
                    .filter(s -> s.season() >= current - 2 && s.minutes() >= scout.cohorts().minMinutes()).toList();
            if (recent.isEmpty()) {
                throw new IllegalStateException(p.name() + " has no season of " + scout.cohorts().minMinutes()
                        + "+ minutes in the last three, so there is no current level to replace");
            }
            Position pos = scout.mainPosition(recent, p);
            Role role = scout.bestRole(recent, pos);
            Projector.Projection own = projector.project(p.id(), pos, current, projector.defaultMethod());
            double[] target = own != null ? own.z() : scout.shrunk(recent, pos, projector.regressionMinutes());
            leaving.add(p);
            positions.add(pos);
            roles.add(role);

            Map<Stat, Double> weights = new EnumMap<>(Stat.class);
            Roles.positionStats(pos).forEach((s, w) -> weights.put(s, 0.35 * w));
            role.weights().forEach((s, w) -> weights.put(s, Math.max(0.5, Math.abs(w))));
            List<String> ids = new ArrayList<>();
            List<Double> dists = new ArrayList<>();
            for (Player c : data.players()) {
                if (excluded.contains(c.id())) {
                    continue;
                }
                List<PlayerSeason> cr = data.seasonsOf(c.id()).stream()
                        .filter(s -> s.season() >= current - 1 && s.minutes() >= minMinutes).toList();
                if (cr.isEmpty()) {
                    continue;
                }
                PlayerSeason latest = cr.getLast();
                Position cp = latest.position() != null ? latest.position() : c.position();
                if (cp == null || !Roles.neighbours(pos).contains(cp) || !leagues.contains(latest.league())) {
                    continue;
                }
                int age = c.ageAtSeason(current + 1);
                if (q.maxAge() != null && (age < 0 || age > q.maxAge())) {
                    continue;
                }
                double[] prof = projCache.computeIfAbsent(c.id() + "|" + pos, k -> {
                    Projector.Projection pr = projector.project(c.id(), pos, current, projector.defaultMethod());
                    return pr == null ? scout.profile(cr, pos) : pr.z();
                });
                double dist = Scout.distance(target, prof, weights, role)[0];
                if (!Double.isNaN(dist)) {
                    ids.add(c.id());
                    dists.add(dist);
                }
            }
            double[] keys = dists.stream().mapToDouble(Double::doubleValue).toArray();
            int[] top = Selection.topK(keys, perRole, new Random(d), work);
            for (int i : top) {
                String id = ids.get(i);
                int j = candIndex.computeIfAbsent(id, k -> {
                    candIds.add(k);
                    return candIds.size() - 1;
                });
                double sim = Math.round(1000 * Math.exp(-keys[i] * keys[i] / 4)) / 10.0;
                edges.add(new Edge(d, j, sim));
            }
        }

        int left = leaving.size();
        Matching best = BipartiteMatcher.bottleneck(left, candIds.size(), edges);
        List<String> unfilled = new ArrayList<>();
        if (best == null) { // some role has too few distinct candidates: fill as many as possible
            best = BipartiteMatcher.tiered(left, candIds.size(), edges, 80, 60, 40, 20, 0);
        }
        Matching greedy = BipartiteMatcher.greedy(left, candIds.size(), edges);
        List<Assignment> chosen = assignments(best, leaving, positions, roles, candIds, edges, current);
        List<Assignment> greedyOut = assignments(greedy, leaving, positions, roles, candIds, edges, current);
        for (int d = 0; d < left; d++) {
            if (best.rightOf()[d] < 0) {
                unfilled.add(leaving.get(d).name());
            }
        }
        return new Result(chosen, best.size() == 0 ? Double.NaN : best.bottleneck(), greedyOut,
                greedy.size() == 0 ? Double.NaN : greedy.bottleneck(), greedy.size(), unfilled, candIds.size(),
                edges.size(), best.work() + work.get(), PlayerSeason.label(current + 1),
                "Each role keeps its top " + perRole + " candidates (quickselect). Bottleneck matching fills every "
                        + "role with a different player and makes the weakest replacement as close as possible; "
                        + "greedy takes the best pair first and never reconsiders.");
    }

    private List<Assignment> assignments(Matching m, List<Player> leaving, List<Position> positions, List<Role> roles,
                                         List<String> candIds, List<Edge> edges, int current) {
        Map<String, Double> score = new LinkedHashMap<>();
        for (Edge e : edges) {
            score.put(e.left() + ":" + e.right(), e.score());
        }
        List<Assignment> out = new ArrayList<>();
        for (int d = 0; d < leaving.size(); d++) {
            int j = m.rightOf()[d];
            Player p = leaving.get(d);
            if (j < 0) {
                out.add(new Assignment(p.id(), p.name(), positions.get(d).name(), roles.get(d).name(), null, null, -1,
                        null, null, null, 0, Double.NaN, null, null));
                continue;
            }
            Player c = data.player(candIds.get(j));
            PlayerSeason latest = data.seasonsOf(c.id()).getLast();
            Position cp = latest.position() != null ? latest.position() : c.position();
            out.add(new Assignment(p.id(), p.name(), positions.get(d).name(), roles.get(d).name(), c.id(), c.name(),
                    c.ageAtSeason(current + 1), cp == null ? null : cp.name(), latest.club(), latest.league().label(),
                    c.marketValueEur(), score.get(d + ":" + j), Scout.positionNote(cp, positions.get(d)),
                    Scout.leagueNote(latest.league(), scout.cohorts().strength())));
        }
        return out;
    }
}

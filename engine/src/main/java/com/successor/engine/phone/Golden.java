package com.successor.engine.phone;

import com.successor.engine.ScoutingEngine;
import com.successor.engine.data.Dataset;
import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.scouting.Comparer;
import com.successor.engine.scouting.PlayerSearch;
import com.successor.engine.scouting.Scout;
import com.successor.engine.scouting.SquadPlanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java's answers to fixed queries ({@code golden.json}), so the Swift tests can check the phone reproduces the Mac
 * exactly. Test players are chosen from the data itself (the busiest player at each position last season), so the
 * same tests run against the synthetic sample and the real data.
 */
final class Golden {
    private Golden() {
    }

    static void write(ScoutingEngine e, Path out) throws IOException {
        Dataset d = e.data();
        Scout scout = e.scout();
        int current = scout.currentSeason();
        Map<Position, String> busiest = new EnumMap<>(Position.class);
        Map<Position, Double> minutes = new EnumMap<>(Position.class);
        for (Player p : d.players()) {
            for (PlayerSeason s : d.seasonsOf(p.id())) {
                Position x = s.position() != null ? s.position() : p.position();
                if (s.season() == current - 1 && x != null && s.minutes() > minutes.getOrDefault(x, 0.0)) {
                    minutes.put(x, s.minutes());
                    busiest.put(x, p.id());
                }
            }
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String id : List.of("tm:88755", "tm:702869")) { // De Bruyne, Saibari (real data only)
            if (d.hasPlayer(id)) {
                targets.add(id);
            }
        }
        targets.addAll(busiest.values());
        String am = busiest.getOrDefault(Position.AM, targets.iterator().next());

        Json j = new Json().raw("{").key("successor").raw("[");
        List<Scout.Request> requests = new ArrayList<>();
        for (String id : targets) {
            requests.add(new Scout.Request(id, null, null, null, null, null, null, null, null, 10, null, null));
        }
        List<String> big5 = Arrays.stream(League.values()).filter(League::big5).map(League::label).toList();
        for (String id : new LinkedHashSet<>(List.of(targets.iterator().next(), am))) {
            requests.add(new Scout.Request(id, null, null, null, null, null, null, null, null, 15, null, true));
            requests.add(new Scout.Request(id, null, null, null, null, null, null, null, null, 10, false, null));
            requests.add(new Scout.Request(id, null, null, null, null, 24, big5, null, 1800, 12, null, null));
        }
        int n = 0;
        for (Scout.Request q : requests) {
            Scout.Result r;
            try {
                r = scout.successor(q);
            } catch (RuntimeException ex) {
                continue;
            }
            j.raw(n++ > 0 ? "," : "").raw("{").key("request").raw("{").key("player").str(q.playerId()).comma()
                    .key("k").num(q.k()).comma().key("maxAge").num(q.maxAge() == null ? Double.NaN : q.maxAge())
                    .comma().key("minMinutes").num(q.minMinutes() == null ? Double.NaN : q.minMinutes()).comma()
                    .key("leagues").raw(q.leagues() == null ? "null" : new Json().strings(q.leagues()).done())
                    .comma().key("useProjection").raw(String.valueOf(!Boolean.FALSE.equals(q.useProjection())))
                    .comma().key("samePositionOnly").raw(String.valueOf(Boolean.TRUE.equals(q.samePositionOnly())))
                    .raw("}").comma();
            j.key("position").str(r.target().position()).comma().key("role").str(r.target().role()).comma()
                    .key("peak").ints(r.target().peak().seasonYears().stream().mapToInt(Integer::intValue).toArray())
                    .comma().key("rating").num(r.target().peak().rating()).comma().key("pool").num(r.pool()).comma()
                    .key("skipped").num(r.skippedForMissingStats()).comma().key("candidates").raw("[");
            int c = 0;
            for (Scout.Candidate x : r.candidates()) {
                j.raw(c++ > 0 ? "," : "").raw("{").key("id").str(x.id()).comma().key("similarity")
                        .num(x.similarity()).comma().key("distance").num(x.distance()).comma().key("statsCompared")
                        .num(x.statsCompared()).comma().key("positionNote").str(x.positionNote()).comma()
                        .key("leagueNote").str(x.leagueNote()).comma().key("closest").strings(x.closest()).comma()
                        .key("differences").strings(x.differences()).raw("}");
            }
            j.raw("]}");
        }
        j.raw("]").comma();

        List<String> three = new ArrayList<>(targets).subList(0, Math.min(3, targets.size()));
        j.key("analyse").raw("[");
        n = 0;
        for (String id : three) {
            j.raw(n++ > 0 ? "," : "").raw("{").key("player").str(id).comma().key("seasons").raw("[");
            int k = 0;
            for (Scout.SeasonAnalysis a : scout.analyse(id)) {
                j.raw(k++ > 0 ? "," : "").raw("{").key("season").num(a.season()).comma().key("club").str(a.club())
                        .comma().key("eligible").raw(String.valueOf(a.eligible())).comma().key("roles").raw("[");
                int m = 0;
                for (Scout.RoleFit f : a.roles()) {
                    j.raw(m++ > 0 ? "," : "").raw("[").str(f.roleId()).comma().num(f.fit()).comma()
                            .num(f.coverage()).raw("]");
                }
                j.raw("]").comma().key("stats").raw("[");
                m = 0;
                for (Scout.StatLine l : a.stats()) {
                    j.raw(m++ > 0 ? "," : "").raw("[").str(l.stat()).comma().num(l.per90()).comma()
                            .num(l.percentile()).comma().num(l.z()).raw("]");
                }
                j.raw("]}");
            }
            j.raw("]}");
        }
        j.raw("]").comma();

        j.key("projection").raw("[");
        n = 0;
        for (String id : three) {
            Scout.ProjectionView v;
            try {
                v = scout.projection(id, null);
            } catch (RuntimeException ex) {
                continue;
            }
            j.raw(n++ > 0 ? "," : "").raw("{").key("player").str(id).comma().key("position").str(v.position())
                    .comma().key("stats").raw("[");
            int k = 0;
            for (Scout.ProjectedStat s : v.stats()) {
                j.raw(k++ > 0 ? "," : "").raw("[").str(s.stat()).comma().num(s.lastPer90()).comma().num(s.per90())
                        .comma().num(s.low()).comma().num(s.high()).raw("]");
            }
            j.raw("]}");
        }
        j.raw("]").comma();

        j.key("squad").raw("[");
        List<String> leaving = new ArrayList<>();
        for (Position p : List.of(Position.AM, Position.DM, Position.W)) {
            if (busiest.containsKey(p)) {
                leaving.add(busiest.get(p));
            }
        }
        if (d.hasPlayer("tm:88755") && d.hasPlayer("tm:357565") && d.hasPlayer("tm:241641")) {
            leaving = List.of("tm:88755", "tm:357565", "tm:241641"); // De Bruyne, Rodri, Bernardo
        }
        try {
            SquadPlanner.Result s = e.squadPlanner().plan(new SquadPlanner.Request(leaving, 27, null, null, 20, null));
            j.raw("{").key("departures").strings(leaving).comma().key("maxAge").num(27).comma()
                    .key("perRole").num(20).comma().key("weakest").num(s.weakest()).comma()
                    .key("greedyWeakest").num(s.greedyWeakest()).comma().key("matching").raw(pairs(s.matching()))
                    .comma().key("greedy").raw(pairs(s.greedy())).raw("}");
        } catch (RuntimeException ignored) {
            // nothing to check
        }
        j.raw("]").comma();

        j.key("compare").raw("[");
        List<String> trio = new ArrayList<>();
        for (Position p : List.of(Position.AM, Position.W, Position.CM)) {
            if (busiest.containsKey(p) && !trio.contains(busiest.get(p))) {
                trio.add(busiest.get(p));
            }
        }
        try {
            Comparer.Result c = e.comparer().compare(trio, null);
            j.raw("{").key("ids").strings(trio).comma().key("position").str(c.position()).comma().key("role")
                    .str(c.role()).comma().key("similarity").raw("[");
            for (int a = 0; a < c.similarity().length; a++) {
                j.raw(a > 0 ? "," : "").nums(c.similarity()[a]);
            }
            j.raw("]").comma().key("players").raw("[");
            int k = 0;
            for (Comparer.Column col : c.players()) {
                j.raw(k++ > 0 ? "," : "").raw("{").key("id").str(col.id()).comma().key("stats").raw("{");
                int m = 0;
                for (Map.Entry<String, Comparer.Value> v : col.stats().entrySet()) {
                    j.raw(m++ > 0 ? "," : "").key(v.getKey()).nums(new double[]{v.getValue().z(),
                            v.getValue().per90(), v.getValue().percentile()});
                }
                j.raw("}}");
            }
            j.raw("]}");
        } catch (RuntimeException ignored) {
            // fewer than two test players
        }
        j.raw("]").comma();

        j.key("search").raw("[");
        PlayerSearch search = e.search();
        n = 0;
        List<String> queries = new ArrayList<>();
        for (String id : targets) {
            String name = d.player(id).name();
            queries.add(name);
            queries.add(name.split(" ")[name.split(" ").length - 1].toLowerCase());
            queries.add(name.substring(0, Math.min(3, name.length())));
        }
        queries.addAll(List.of("rodri", "müller", "odegaard", "vini jr", "ab"));
        for (String q : queries) {
            PlayerSearch.Result r;
            try {
                r = search.search(q, 8);
            } catch (RuntimeException ex) {
                continue;
            }
            j.raw(n++ > 0 ? "," : "").raw("{").key("query").str(q).comma().key("matched").num(r.matched()).comma()
                    .key("hits").strings(r.hits().stream().map(PlayerSearch.Hit::id).toList()).raw("}");
        }
        j.raw("]}");
        Files.writeString(out.resolve("golden.json"), j.done());
    }

    private static String pairs(List<SquadPlanner.Assignment> as) {
        Json j = new Json().raw("[");
        int i = 0;
        for (SquadPlanner.Assignment a : as) {
            j.raw(i++ > 0 ? "," : "").raw("[").str(a.departureId()).comma().str(a.candidateId()).comma()
                    .num(a.similarity()).raw("]");
        }
        return j.raw("]").done();
    }
}

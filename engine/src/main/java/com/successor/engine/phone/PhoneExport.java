package com.successor.engine.phone;

import com.successor.engine.ScoutingEngine;
import com.successor.engine.data.Dataset;
import com.successor.engine.data.Names;
import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;
import com.successor.engine.scouting.Cohorts;
import com.successor.engine.scouting.LeagueStrength;
import com.successor.engine.scouting.Projector;
import com.successor.engine.scouting.Roles;
import com.successor.engine.scouting.Scout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports what the iPhone app needs to run the scouting queries on the phone, with no server:
 * <ul>
 *   <li>{@code meta.json}: stats, positions, leagues (with league-strength factors), role templates, players (with
 *       their name tokens, normalised here so search matches the Mac exactly), player-season index, cohort scales,
 *       projections index, calibration and the backtest summary;</li>
 *   <li>{@code seasons.bin}: each player-season's raw stats, sparse (a 64-bit mask of measured stats, then those
 *       values as little-endian doubles, in stat order);</li>
 *   <li>{@code cohorts.bin}: each cohort stat's per-90 values in ascending order (doubles), for exact percentiles;</li>
 *   <li>{@code projections.bin}: next-season projections, computed here by the Java projector (Marcel + comparables)
 *       for every recently active player at every position he can be compared at: z, low, high per position stat.</li>
 * </ul>
 * The heavy statistics (linking, cohorts, league strength, projections) stay in Java; the phone does lookups,
 * similarity, top-k and matching. {@code golden.json} records Java's answers to fixed queries so the Swift tests can
 * check the phone reproduces them.
 *
 * <p>Run: {@code java -cp engine/target/classes com.successor.engine.phone.PhoneExport data/processed ios/Data/real}
 */
public final class PhoneExport {
    public static final int FORMAT = 1;

    private PhoneExport() {
    }

    public static void main(String[] args) throws IOException {
        Path data = Path.of(args.length > 0 ? args[0] : "data/processed");
        Path out = Path.of(args.length > 1 ? args[1] : "ios/Data/real");
        long t0 = System.nanoTime();
        ScoutingEngine e = ScoutingEngine.load(data);
        Files.createDirectories(out);
        String summary = export(e, out);
        Golden.write(e, out);
        System.out.printf("%s%nexported to %s in %.1f s%n", summary, out, (System.nanoTime() - t0) / 1e9);
    }

    static String export(ScoutingEngine engine, Path out) throws IOException {
        Dataset d = engine.data();
        Scout scout = engine.scout();
        Cohorts cohorts = scout.cohorts();
        Projector projector = scout.projector();
        LeagueStrength strength = cohorts.strength();
        int current = scout.currentSeason();
        List<Player> players = new ArrayList<>(d.players());
        Map<String, Integer> playerIndex = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) {
            playerIndex.put(players.get(i).id(), i);
        }

        Json j = new Json().raw("{");
        j.key("format").num(FORMAT).comma();
        j.key("origin").str(engine.summary().origin()).comma();
        j.key("currentSeason").num(current).comma();
        j.key("minMinutes").num(cohorts.minMinutes()).comma();
        j.key("projectionMinMinutes").num(Projector.MIN_SEASON_MINUTES).comma();
        j.key("regressionMinutes").num(projector.regressionMinutes()).comma();
        j.key("rangeScale").num(projector.rangeScale()).comma();
        j.key("method").str(projector.defaultMethod().name()).comma();

        j.key("stats").raw("[");
        for (Stat s : Stat.values()) {
            j.raw(s.ordinal() > 0 ? "," : "").raw("{").key("key").str(s.key()).comma().key("label").str(s.label())
                    .comma().key("per90").raw(Boolean.toString(s.per90())).comma().key("group").str(s.group().name())
                    .raw("}");
        }
        j.raw("]").comma();

        j.key("positions").raw("[");
        for (Position p : Position.values()) {
            j.raw(p.ordinal() > 0 ? "," : "").raw("{").key("code").str(p.name()).comma().key("label").str(p.label())
                    .comma().key("neighbours").ints(Roles.neighbours(p).stream().mapToInt(Enum::ordinal).toArray())
                    .comma().key("stats").raw("[");
            int k = 0;
            for (Map.Entry<Stat, Double> e : Roles.positionStats(p).entrySet()) { // insertion order, as Java iterates
                j.raw(k++ > 0 ? "," : "").raw("[").num(e.getKey().ordinal()).comma().num(e.getValue()).raw("]");
            }
            j.raw("]}");
        }
        j.raw("]").comma();

        j.key("leagues").raw("[");
        for (League l : League.values()) {
            double[] f = new double[Stat.COUNT];
            for (Stat s : Stat.values()) {
                f[s.ordinal()] = strength.factor(l, s);
            }
            j.raw(l.ordinal() > 0 ? "," : "").raw("{").key("code").str(l.name()).comma().key("label").str(l.label())
                    .comma().key("country").str(l.country()).comma().key("big5").raw(Boolean.toString(l.big5()))
                    .comma().key("headline").str(strength.headline(l)).comma().key("factors").nums(f).raw("}");
        }
        j.raw("]").comma();

        j.key("roles").raw("[");
        int ri = 0;
        for (Roles.Role r : Roles.all()) {
            j.raw(ri++ > 0 ? "," : "").raw("{").key("id").str(r.id()).comma().key("name").str(r.name()).comma()
                    .key("position").num(r.position().ordinal()).comma().key("description").str(r.description())
                    .comma().key("weights").raw("[");
            int k = 0;
            for (Map.Entry<Stat, Double> e : r.weights().entrySet()) {
                j.raw(k++ > 0 ? "," : "").raw("[").num(e.getKey().ordinal()).comma().num(e.getValue()).raw("]");
            }
            j.raw("]}");
        }
        j.raw("]").comma();

        // players
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> births = new ArrayList<>();
        List<String> nations = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        List<String> feet = new ArrayList<>();
        int[] birthYear = new int[players.size()];
        int[] position = new int[players.size()];
        int[] height = new int[players.size()];
        double[] value = new double[players.size()];
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            ids.add(p.id());
            names.add(p.name());
            births.add(p.birthDate() == null ? "" : p.birthDate().toString());
            nations.add(p.nation());
            subs.add(p.subPosition());
            feet.add(p.foot());
            birthYear[i] = p.birthYear();
            position[i] = p.position() == null ? -1 : p.position().ordinal();
            height[i] = p.heightCm();
            value[i] = p.marketValueEur();
        }
        j.key("players").raw("{").key("id").strings(ids).comma().key("name").strings(names).comma()
                .key("birthDate").strings(births).comma().key("birthYear").ints(birthYear).comma()
                .key("nation").strings(nations).comma().key("position").ints(position).comma()
                .key("subPosition").strings(subs).comma().key("foot").strings(feet).comma()
                .key("height").ints(height).comma().key("value").nums(value).comma().key("aliases").raw("[");
        for (int i = 0; i < players.size(); i++) {
            j.raw(i > 0 ? "," : "").strings(players.get(i).aliases());
        }
        j.raw("]").comma().key("tokens").raw("[");
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            j.raw(i > 0 ? "," : "").raw("[");
            List<String> spellings = new ArrayList<>();
            spellings.add(p.name());
            spellings.addAll(p.aliases());
            for (int k = 0; k < spellings.size(); k++) {
                j.raw(k > 0 ? "," : "").strings(Names.tokens(spellings.get(k)));
            }
            j.raw("]");
        }
        j.raw("]}").comma();

        // player-seasons, in each player's order
        Map<String, Integer> clubIndex = new LinkedHashMap<>();
        List<Integer> sPlayer = new ArrayList<>();
        List<Integer> sSeason = new ArrayList<>();
        List<Integer> sLeague = new ArrayList<>();
        List<Integer> sClub = new ArrayList<>();
        List<Integer> sPos = new ArrayList<>();
        List<Integer> sSources = new ArrayList<>();
        ByteArrayOutputStream seasonsBin = new ByteArrayOutputStream(1 << 24);
        for (int i = 0; i < players.size(); i++) {
            for (PlayerSeason s : d.seasonsOf(players.get(i).id())) {
                sPlayer.add(i);
                sSeason.add(s.season());
                sLeague.add(s.league().ordinal());
                sClub.add(clubIndex.computeIfAbsent(s.club() == null ? "" : s.club(), k -> clubIndex.size()));
                sPos.add(s.position() == null ? -1 : s.position().ordinal());
                int bits = 0;
                for (Source src : s.sources()) {
                    bits |= 1 << src.ordinal();
                }
                sSources.add(bits);
                long mask = 0;
                List<Double> vals = new ArrayList<>();
                for (Stat st : Stat.values()) {
                    double v = s.get(st);
                    if (!Double.isNaN(v)) {
                        mask |= 1L << st.ordinal();
                        vals.add(v);
                    }
                }
                ByteBuffer b = ByteBuffer.allocate(8 + 8 * vals.size()).order(ByteOrder.LITTLE_ENDIAN);
                b.putLong(mask);
                vals.forEach(b::putDouble);
                seasonsBin.write(b.array());
            }
        }
        j.key("seasons").raw("{").key("player").ints(toArray(sPlayer)).comma().key("season").ints(toArray(sSeason))
                .comma().key("league").ints(toArray(sLeague)).comma().key("club").ints(toArray(sClub)).comma()
                .key("position").ints(toArray(sPos)).comma().key("sources").ints(toArray(sSources)).raw("}").comma();
        j.key("sources").strings(java.util.Arrays.stream(Source.values()).map(Enum::name).toList()).comma();
        j.key("clubs").strings(clubIndex.keySet()).comma();

        // cohorts: every Big-5 position-season
        int first = sSeason.stream().mapToInt(Integer::intValue).min().orElse(current);
        ByteArrayOutputStream cohortsBin = new ByteArrayOutputStream(1 << 22);
        long cohortOffset = 0;
        j.key("cohorts").raw("[");
        int ci = 0;
        for (int y = first; y <= current; y++) {
            for (Position p : Position.values()) {
                Cohorts.Cohort c = cohorts.cohort(p, y);
                if (c.players == 0) {
                    continue;
                }
                j.raw(ci++ > 0 ? "," : "").raw("{").key("pos").num(p.ordinal()).comma().key("season").num(y).comma()
                        .key("players").num(c.players).comma().key("stats").raw("[");
                int k = 0;
                for (Stat st : Stat.values()) {
                    Cohorts.StatScale sc = c.stat(st);
                    if (sc == null) {
                        continue;
                    }
                    double[] v = sc.sortedValues();
                    j.raw(k++ > 0 ? "," : "").nums(new double[]{st.ordinal(), sc.median, sc.q1, sc.q3, sc.scale,
                            cohortOffset, v.length});
                    ByteBuffer b = ByteBuffer.allocate(8 * v.length).order(ByteOrder.LITTLE_ENDIAN);
                    for (double x : v) {
                        b.putDouble(x);
                    }
                    cohortsBin.write(b.array());
                    cohortOffset += v.length;
                }
                j.raw("]}");
            }
        }
        j.raw("]").comma();

        // projections: every player active in the last three seasons, at each position whose comparison pool
        // (neighbours) contains a position he played recently
        ByteArrayOutputStream projBin = new ByteArrayOutputStream(1 << 23);
        long projOffset = 0;
        int projections = 0;
        j.key("projections").raw("[");
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            Set<Position> played = new LinkedHashSet<>();
            for (PlayerSeason s : d.seasonsOf(p.id())) {
                if (s.season() >= current - 2) {
                    Position x = s.position() != null ? s.position() : p.position();
                    if (x != null) {
                        played.add(x);
                    }
                }
            }
            if (played.isEmpty()) {
                continue;
            }
            // the player page projects him at his main position over his last three seasons of 270+ minutes
            List<PlayerSeason> eligible = d.seasonsOf(p.id()).stream()
                    .filter(s -> s.minutes() >= Projector.MIN_SEASON_MINUTES).toList();
            Position own = null;
            if (!eligible.isEmpty()) {
                try {
                    own = scout.mainPosition(eligible.subList(Math.max(0, eligible.size() - 3), eligible.size()), p);
                } catch (RuntimeException ignored) {
                    // no known position
                }
            }
            for (Position pos : Position.values()) {
                if (pos != own && Roles.neighbours(pos).stream().noneMatch(played::contains)) {
                    continue;
                }
                Projector.Projection pr = projector.project(p.id(), pos, current, projector.defaultMethod());
                if (pr == null) {
                    continue;
                }
                List<Stat> stats = new ArrayList<>(Roles.positionStats(pos).keySet());
                ByteBuffer b = ByteBuffer.allocate(8 * 3 * stats.size()).order(ByteOrder.LITTLE_ENDIAN);
                for (Stat st : stats) {
                    b.putDouble(pr.z()[st.ordinal()]);
                    b.putDouble(pr.low()[st.ordinal()]);
                    b.putDouble(pr.high()[st.ordinal()]);
                }
                projBin.write(b.array());
                j.raw(projections++ > 0 ? "," : "").raw("{").key("player").num(i).comma().key("pos")
                        .num(pos.ordinal()).comma().key("age").num(pr.age()).comma().key("comparables")
                        .num(pr.comparables()).comma().key("basedOn")
                        .ints(pr.basedOn().stream().mapToInt(Integer::intValue).toArray()).comma().key("offset")
                        .num(projOffset).raw("}");
                projOffset += 3L * stats.size();
            }
        }
        j.raw("]").comma();

        // backtest summary for the "how it works" screen
        Projector.Backtest bt = engine.backtest();
        j.key("backtest").raw("{").key("testSeasons").ints(bt.testSeasons().stream().mapToInt(Integer::intValue)
                .toArray()).comma().key("trainSeasons").ints(bt.trainSeasons().stream().mapToInt(Integer::intValue)
                .toArray()).comma().key("chosenMethod").str(bt.chosenMethod().name()).comma().key("overall").raw("[");
        int mi = 0;
        for (Projector.MethodScore m : bt.overall()) {
            j.raw(mi++ > 0 ? "," : "").raw("{").key("method").str(m.method().name()).comma().key("mae").num(m.mae())
                    .comma().key("vsNaive").num(m.improvementVsNaive()).comma().key("coverage80").num(m.coverage80())
                    .raw("}");
        }
        j.raw("]");
        Projector.LeagueCheck lc = bt.leagueCheck();
        if (lc != null) {
            j.comma().key("leagueCheck").raw("{").key("arrivals").num(lc.arrivals()).comma().key("arrivalsMae")
                    .num(lc.arrivalsMae()).comma().key("arrivalsMaeUnadjusted").num(lc.arrivalsMaeUnadjusted())
                    .comma().key("overallMae").num(lc.overallMae()).comma().key("overallMaeUnadjusted")
                    .num(lc.overallMaeUnadjusted()).comma().key("factorsFrom").str(lc.factorsFrom()).raw("}");
        }
        j.raw("}");
        j.raw("}");

        Files.writeString(out.resolve("meta.json"), j.done());
        Files.write(out.resolve("seasons.bin"), seasonsBin.toByteArray());
        Files.write(out.resolve("cohorts.bin"), cohortsBin.toByteArray());
        Files.write(out.resolve("projections.bin"), projBin.toByteArray());
        return String.format(java.util.Locale.ROOT, "%,d players, %,d player-seasons, %,d cohorts, %,d projections; "
                        + "meta %.1f MB, seasons %.1f MB, cohorts %.1f MB, projections %.1f MB", players.size(),
                sPlayer.size(), ci, projections, Files.size(out.resolve("meta.json")) / 1e6,
                seasonsBin.size() / 1e6, cohortsBin.size() / 1e6, projBin.size() / 1e6);
    }

    private static int[] toArray(List<Integer> xs) {
        return xs.stream().mapToInt(Integer::intValue).toArray();
    }
}

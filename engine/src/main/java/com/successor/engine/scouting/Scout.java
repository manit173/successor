package com.successor.engine.scouting;

import com.successor.engine.algo.Selection;
import com.successor.engine.algo.Work;
import com.successor.engine.data.Dataset;
import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Stat;
import com.successor.engine.scouting.Cohorts.Cohort;
import com.successor.engine.scouting.Cohorts.StatScale;
import com.successor.engine.scouting.Roles.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * The scouting logic: role fits, per-season analysis, peak detection and the successor search.
 *
 * <p><b>Successor search</b>
 * <ol>
 *   <li>Position: where the target played most minutes (or the caller's choice).</li>
 *   <li>Role: the role at that position he fits best on average.</li>
 *   <li>Peak: the run of up to 3 consecutive eligible seasons with the highest mean role fit (or the caller's
 *       seasons). His peak profile is the minutes-weighted mean of his robust z-scores over those seasons.</li>
 *   <li>Candidates: every other player active in the last two seasons, at that position or a neighbouring one, within
 *       the filters. His profile is his <b>projected next season</b> ({@link Projector}), scaled against the
 *       <em>target's</em> position, so the question is "how would he look as one next season". Projections are
 *       regressed toward the median, so the peak profile is shrunk by the same rule (like with like).</li>
 *   <li>Similarity: weighted distance over the stats both profiles have:
 *       {@code d = sqrt(Σ w·(a − b)² / Σ w)}; the role's stats weigh 0.5–1, the position's other stats 0.35.
 *       Shown as {@code 100·exp(−d²/4)}%.</li>
 *   <li>Top-k by <b>randomized quickselect</b> on d (expected O(n)), then only the k winners are sorted.</li>
 * </ol>
 */
public final class Scout {
    public static final int DEFAULT_MIN_MINUTES = 900;
    private static final double OTHER_STAT_WEIGHT = 0.35;

    private final Dataset data;
    private final Cohorts cohorts;
    private final Projector projector;
    private final int currentSeason;

    public Scout(Dataset data, int minMinutes) {
        this.data = data;
        this.cohorts = new Cohorts(data, minMinutes);
        this.projector = new Projector(data, cohorts);
        this.currentSeason = data.seasons().stream().mapToInt(PlayerSeason::season).max().orElse(0);
    }

    public Cohorts cohorts() {
        return cohorts;
    }

    public Projector projector() {
        return projector;
    }

    /** The most recent season in the data (start year). */
    public int currentSeason() {
        return currentSeason;
    }

    // ------------------------------------------------------------------------------------------------ analysis

    public record RoleFit(String roleId, String name, double fit, double coverage) {
    }

    public record StatLine(String stat, String label, double per90, double percentile, double z, double cohortMedian,
                           int cohortSize) {
    }

    public record SeasonAnalysis(int season, String label, String club, String league, String position, int minutes,
                                 boolean eligible, List<RoleFit> roles, List<StatLine> stats) {
    }

    /** Every season of a player: role fits and percentiles against his position-season cohort. */
    public List<SeasonAnalysis> analyse(String playerId) {
        Player p = data.player(playerId);
        List<SeasonAnalysis> out = new ArrayList<>();
        for (PlayerSeason s : data.seasonsOf(p.id())) {
            Position pos = s.position() != null ? s.position() : p.position();
            boolean eligible = pos != null && s.minutes() >= cohorts.minMinutes();
            List<RoleFit> fits = new ArrayList<>();
            List<StatLine> lines = new ArrayList<>();
            if (eligible) {
                Cohort c = cohorts.cohort(pos, s.season());
                for (Role r : Roles.of(pos)) {
                    fits.add(fit(s, r, c));
                }
                fits.sort(Comparator.comparingDouble((RoleFit f) -> Double.isNaN(f.fit()) ? -1 : f.fit()).reversed());
                for (Stat st : Roles.positionStats(pos).keySet()) {
                    StatScale sc = c.stat(st);
                    double v = Cohorts.per90(s, st);
                    double adj = cohorts.value(s, st); // league-strength adjusted outside the Big 5
                    if (sc != null && !Double.isNaN(v)) {
                        lines.add(new StatLine(st.key(), st.label(), v, sc.percentile(adj), sc.z(adj), sc.median,
                                sc.size()));
                    }
                }
            }
            out.add(new SeasonAnalysis(s.season(), s.seasonLabel(), s.club(), s.league().label(),
                    pos == null ? null : pos.name(), (int) s.minutes(), eligible, fits, lines));
        }
        return out;
    }

    /** Weighted mean percentile over the role's stats that were measured; NaN if under a third of the weight was. */
    RoleFit fit(PlayerSeason s, Role r, Cohort c) {
        double sum = 0;
        double wSum = 0;
        double wAll = 0;
        for (Map.Entry<Stat, Double> e : r.weights().entrySet()) {
            double w = Math.abs(e.getValue());
            wAll += w;
            StatScale sc = c.stat(e.getKey());
            double v = cohorts.value(s, e.getKey());
            if (sc == null || Double.isNaN(v)) {
                continue;
            }
            double pct = sc.percentile(v);
            sum += w * (e.getValue() < 0 ? 100 - pct : pct);
            wSum += w;
        }
        double coverage = wAll == 0 ? 0 : wSum / wAll;
        return new RoleFit(r.id(), r.name(), coverage < 1.0 / 3 ? Double.NaN : sum / wSum, coverage);
    }

    // ------------------------------------------------------------------------------------------------ successor

    /** Search parameters; null fields take defaults. */
    public record Request(String playerId, Integer peakFrom, Integer peakTo, String position, String role,
                         Integer maxAge, List<String> leagues, List<String> positions, Integer minMinutes, Integer k,
                         Boolean useProjection, Boolean samePositionOnly) {
        public Request(String playerId, Integer peakFrom, Integer peakTo, String position, String role, Integer maxAge,
                       List<String> leagues, List<String> positions, Integer minMinutes, Integer k) {
            this(playerId, peakFrom, peakTo, position, role, maxAge, leagues, positions, minMinutes, k, null, null);
        }

        public Request(String playerId, Integer peakFrom, Integer peakTo, String position, String role, Integer maxAge,
                       List<String> leagues, List<String> positions, Integer minMinutes, Integer k,
                       Boolean useProjection) {
            this(playerId, peakFrom, peakTo, position, role, maxAge, leagues, positions, minMinutes, k, useProjection,
                    null);
        }
    }

    public record Peak(List<String> seasons, List<Integer> seasonYears, double rating, boolean automatic) {
    }

    public record Target(String id, String name, String position, String role, String roleName, Peak peak,
                         Map<String, Double> profile) {
    }

    public record Candidate(String id, String name, int ageNextSeason, String position, String club, String league,
                            List<String> seasonsUsed, String profileKind, int minutes, long marketValueEur,
                            double similarity,
                            double distance, int statsCompared, List<String> closest, List<String> differences,
                            Map<String, Double> profile, boolean samePosition, String positionNote, boolean big5,
                            String leagueNote) {
    }

    /** "Winger: a different position, compared as an attacking midfielder", or null for the same position. */
    static String positionNote(Position own, Position comparedAs) {
        if (own == null || own == comparedAs) {
            return null;
        }
        return own.label() + ": a different position, compared as " + article(comparedAs.label().toLowerCase(Locale.ROOT));
    }

    static String leagueNote(League l, LeagueStrength strength) {
        if (l.big5()) {
            return null;
        }
        String h = strength.headline(l);
        return l.label() + " (" + l.country() + "): outside the Big 5; " + (h == null
                ? "stats not adjusted for league strength"
                : "converted to Big-5 level by measured factors (" + h + ")");
    }

    private static String article(String noun) {
        return ("aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
    }

    public record Result(Target target, List<Candidate> candidates, int pool, int skippedForMissingStats,
                         long selectionWork, long cohortWork, String nextSeason) {
    }

    public Result successor(Request q) {
        if (q == null || q.playerId() == null || q.playerId().isBlank()) {
            throw new IllegalArgumentException("playerId is required");
        }
        Player target = data.player(q.playerId());
        int k = q.k() == null ? 10 : q.k();
        if (k < 1 || k > 100) {
            throw new IllegalArgumentException("k must be between 1 and 100, got " + k);
        }
        int minMinutes = q.minMinutes() == null ? cohorts.minMinutes() : q.minMinutes();
        if (minMinutes < 0 || minMinutes > 4000) {
            throw new IllegalArgumentException("minMinutes must be between 0 and 4000, got " + minMinutes);
        }
        if (q.maxAge() != null && (q.maxAge() < 15 || q.maxAge() > 45)) {
            throw new IllegalArgumentException("maxAge must be between 15 and 45, got " + q.maxAge());
        }
        List<PlayerSeason> eligible = data.seasonsOf(target.id()).stream()
                .filter(s -> s.minutes() >= cohorts.minMinutes()).toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException(target.name() + " has no season with at least " + cohorts.minMinutes()
                    + " minutes, so there is no profile to match");
        }

        Position pos = q.position() != null ? parsePosition(q.position()) : mainPosition(eligible, target);
        Role role = q.role() != null ? Roles.byId(q.role()) : bestRole(eligible, pos);
        if (role.position() != pos) {
            throw new IllegalArgumentException("role " + role.id() + " is a " + role.position() + " role, not " + pos);
        }
        Peak peak = peak(eligible, pos, role, q.peakFrom(), q.peakTo());
        List<PlayerSeason> peakSeasons = eligible.stream().filter(s -> peak.seasonYears().contains(s.season()))
                .toList();
        boolean projected = !Boolean.FALSE.equals(q.useProjection());
        // like with like: candidates' projections are regressed toward the median, so the peak is too (same R)
        double[] targetProfile = projected ? shrunk(peakSeasons, pos, projector.regressionMinutes())
                : profile(peakSeasons, pos);

        Map<Stat, Double> weights = new EnumMap<>(Stat.class);
        Roles.positionStats(pos).forEach((s, w) -> weights.put(s, OTHER_STAT_WEIGHT * w));
        role.weights().forEach((s, w) -> weights.put(s, Math.max(0.5, Math.abs(w))));

        // candidate pool
        List<Position> allowed = Boolean.TRUE.equals(q.samePositionOnly()) ? List.of(pos)
                : q.positions() == null || q.positions().isEmpty() ? Roles.neighbours(pos)
                : q.positions().stream().map(Scout::parsePosition).toList();
        List<League> leagues = q.leagues() == null || q.leagues().isEmpty() ? List.of(League.values())
                : q.leagues().stream().map(l -> {
                    League x = League.fromLabel(l);
                    if (x == null) {
                        throw new IllegalArgumentException("unknown league " + l);
                    }
                    return x;
                }).toList();
        List<String> ids = new ArrayList<>();
        List<double[]> profiles = new ArrayList<>();
        List<List<PlayerSeason>> used = new ArrayList<>();
        List<Double> dists = new ArrayList<>();
        List<Integer> compared = new ArrayList<>();
        int skipped = 0;
        for (Player p : data.players()) {
            if (p.id().equals(target.id())) {
                continue;
            }
            List<PlayerSeason> recent = data.seasonsOf(p.id()).stream()
                    .filter(s -> s.season() >= currentSeason - 1 && s.minutes() >= minMinutes).toList();
            if (recent.isEmpty()) {
                continue;
            }
            PlayerSeason latest = recent.getLast();
            Position cp = latest.position() != null ? latest.position() : p.position();
            if (cp == null || !allowed.contains(cp) || !leagues.contains(latest.league())) {
                continue;
            }
            int age = p.ageAtSeason(currentSeason + 1);
            if (q.maxAge() != null && (age < 0 || age > q.maxAge())) {
                continue;
            }
            double[] prof = null;
            if (projected) {
                Projector.Projection pr = projector.project(p.id(), pos, currentSeason, projector.defaultMethod());
                prof = pr == null ? null : pr.z();
            }
            if (prof == null) {
                prof = profile(recent, pos);
            }
            double[] d = distance(targetProfile, prof, weights, role);
            if (Double.isNaN(d[0])) {
                skipped++;
                continue;
            }
            ids.add(p.id());
            profiles.add(prof);
            used.add(recent);
            dists.add(d[0]);
            compared.add((int) d[1]);
        }

        double[] keys = dists.stream().mapToDouble(Double::doubleValue).toArray();
        Work selection = Work.counter();
        int[] top = Selection.topK(keys, k, new Random(42), selection);
        List<Candidate> out = new ArrayList<>(top.length);
        for (int i : top) {
            Player p = data.player(ids.get(i));
            List<PlayerSeason> recent = used.get(i);
            PlayerSeason latest = recent.getLast();
            double d = keys[i];
            Position own = latest.position() != null ? latest.position() : p.position();
            out.add(new Candidate(p.id(), p.name(), p.ageAtSeason(currentSeason + 1), own.name(), latest.club(),
                    latest.league().label(), recent.stream().map(PlayerSeason::seasonLabel).toList(),
                    Boolean.FALSE.equals(q.useProjection()) ? "recent seasons"
                            : "projected " + PlayerSeason.label(currentSeason + 1),
                    (int) recent.stream().mapToDouble(PlayerSeason::minutes).sum(), p.marketValueEur(),
                    round1(100 * Math.exp(-d * d / 4)), round3(d), compared.get(i),
                    explain(targetProfile, profiles.get(i), weights, true),
                    explain(targetProfile, profiles.get(i), weights, false), asMap(profiles.get(i), weights),
                    own == pos, positionNote(own, pos), latest.league().big5(), leagueNote(latest.league(), cohorts.strength())));
        }
        Target t = new Target(target.id(), target.name(), pos.name(), role.id(), role.name(), peak,
                asMap(targetProfile, weights));
        return new Result(t, out, keys.length, skipped, selection.get(), cohorts.work(),
                PlayerSeason.label(currentSeason + 1));
    }

    /** Position with the most minutes over the eligible seasons. */
    public Position mainPosition(List<PlayerSeason> seasons, Player p) {
        Map<Position, Double> minutes = new EnumMap<>(Position.class);
        for (PlayerSeason s : seasons) {
            Position x = s.position() != null ? s.position() : p.position();
            if (x != null) {
                minutes.merge(x, s.minutes(), Double::sum);
            }
        }
        return minutes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElseThrow(() -> new IllegalStateException(p.name() + " has no known position"));
    }

    /** The role at {@code pos} with the highest mean fit over the seasons (scaled against {@code pos}). */
    Role bestRole(List<PlayerSeason> seasons, Position pos) {
        Role best = null;
        double bestFit = -1;
        for (Role r : Roles.of(pos)) {
            double sum = 0;
            int n = 0;
            for (PlayerSeason s : seasons) {
                double f = fit(s, r, cohorts.cohort(pos, s.season())).fit();
                if (!Double.isNaN(f)) {
                    sum += f;
                    n++;
                }
            }
            if (n > 0 && sum / n > bestFit) {
                bestFit = sum / n;
                best = r;
            }
        }
        if (best == null) {
            // no role has a third of its stats measured in these seasons (e.g. a midfielder whose recent seasons are
            // Understat-only): fall back to the role whose stats are best covered, so the search still runs on the
            // stats that do exist
            double bestCoverage = -1;
            for (Role r : Roles.of(pos)) {
                double cov = 0;
                for (PlayerSeason s : seasons) {
                    cov += fit(s, r, cohorts.cohort(pos, s.season())).coverage();
                }
                if (cov > bestCoverage) {
                    bestCoverage = cov;
                    best = r;
                }
            }
        }
        return best;
    }

    /** Best window of up to 3 consecutive eligible seasons by mean role fit, or the caller's range. */
    Peak peak(List<PlayerSeason> eligible, Position pos, Role role, Integer from, Integer to) {
        Map<Integer, Double> fitBySeason = new LinkedHashMap<>();
        Map<Integer, Double> minutesBySeason = new HashMap<>();
        for (PlayerSeason s : eligible) {
            double f = fit(s, role, cohorts.cohort(pos, s.season())).fit();
            if (!Double.isNaN(f)) {
                // a season split between clubs: keep the stint with more minutes
                if (!fitBySeason.containsKey(s.season()) || s.minutes() > minutesBySeason.get(s.season())) {
                    fitBySeason.put(s.season(), f);
                    minutesBySeason.put(s.season(), s.minutes());
                }
            }
        }
        if (from != null || to != null) {
            int a = from == null ? Integer.MIN_VALUE : from;
            int b = to == null ? Integer.MAX_VALUE : to;
            if (a > b) {
                throw new IllegalArgumentException("peakFrom must not be after peakTo");
            }
            List<Integer> chosen = fitBySeason.keySet().stream().filter(y -> y >= a && y <= b).sorted().toList();
            if (chosen.isEmpty()) {
                throw new IllegalArgumentException("no eligible season between " + label(from) + " and "
                        + label(to));
            }
            double r = chosen.stream().mapToDouble(fitBySeason::get).average().orElse(Double.NaN);
            return new Peak(chosen.stream().map(PlayerSeason::label).toList(), chosen, round1(r), false);
        }
        List<Integer> years = fitBySeason.keySet().stream().sorted().toList();
        if (years.isEmpty()) {
            throw new IllegalStateException("not enough measured stats to rate any season for this role");
        }
        int w = Math.min(3, years.size());
        double best = -1;
        int bestStart = 0;
        int bestLen = 1;
        for (int len = w; len >= 1 && best < 0; len--) {
            for (int i = 0; i + len <= years.size(); i++) {
                if (years.get(i + len - 1) - years.get(i) != len - 1) {
                    continue; // not consecutive seasons
                }
                double sum = 0;
                for (int j = i; j < i + len; j++) {
                    sum += fitBySeason.get(years.get(j));
                }
                double mean = sum / len;
                if (mean > best || (mean == best && years.get(i) > years.get(bestStart))) {
                    best = mean;
                    bestStart = i;
                    bestLen = len;
                }
            }
        }
        List<Integer> chosen = years.subList(bestStart, bestStart + bestLen);
        return new Peak(chosen.stream().map(PlayerSeason::label).toList(), List.copyOf(chosen), round1(best), true);
    }

    /** Minutes-weighted mean robust z per stat over the seasons, each scaled in ({@code pos}, its season). */
    double[] profile(List<PlayerSeason> seasons, Position pos) {
        double[] sum = new double[Stat.COUNT];
        double[] w = new double[Stat.COUNT];
        for (PlayerSeason s : seasons) {
            Cohort c = cohorts.cohort(pos, s.season());
            for (Stat st : Roles.positionStats(pos).keySet()) {
                StatScale sc = c.stat(st);
                double v = cohorts.value(s, st);
                if (sc != null && !Double.isNaN(v)) {
                    sum[st.ordinal()] += s.minutes() * sc.z(v);
                    w[st.ordinal()] += s.minutes();
                }
            }
        }
        double[] z = new double[Stat.COUNT];
        for (int i = 0; i < z.length; i++) {
            z[i] = w[i] > 0 ? sum[i] / w[i] : Double.NaN;
        }
        return z;
    }

    /**
     * The peak as an estimate of true level: each stat's minutes-weighted z times n / (n + R), n = minutes behind it.
     * The same shrinkage the projections use, so an observed peak is not compared with regressed projections.
     */
    double[] shrunk(List<PlayerSeason> seasons, Position pos, double r) {
        double[] z = profile(seasons, pos);
        double[] n = new double[Stat.COUNT];
        for (PlayerSeason s : seasons) {
            double[] v = cohorts.zVector(s, pos);
            for (int i = 0; i < Stat.COUNT; i++) {
                if (!Double.isNaN(v[i])) {
                    n[i] += s.minutes();
                }
            }
        }
        for (int i = 0; i < Stat.COUNT; i++) {
            if (!Double.isNaN(z[i])) {
                z[i] *= n[i] / (n[i] + r);
            }
        }
        return z;
    }

    /**
     * {distance, stats compared}; distance NaN when the overlap is too thin to judge: fewer than 3 shared stats, or the
     * candidate has under 40% of the role weight that the target's profile has (e.g. a peak measured by Understat and
     * a candidate measured by FBref share xA, key passes, shots and npxG but not xGChain).
     */
    static double[] distance(double[] a, double[] b, Map<Stat, Double> weights, Role role) {
        double num = 0;
        double den = 0;
        int n = 0;
        double roleW = 0;
        double roleCovered = 0;
        for (Map.Entry<Stat, Double> e : weights.entrySet()) {
            int i = e.getKey().ordinal();
            boolean inRole = role.weights().containsKey(e.getKey());
            if (inRole && !Double.isNaN(a[i])) {
                roleW += e.getValue(); // coverage is judged against what the target's profile has
            }
            if (Double.isNaN(a[i]) || Double.isNaN(b[i])) {
                continue;
            }
            double diff = Math.max(-4, Math.min(4, a[i])) - Math.max(-4, Math.min(4, b[i])); // cap extreme z
            num += e.getValue() * diff * diff;
            den += e.getValue();
            n++;
            if (inRole) {
                roleCovered += e.getValue();
            }
        }
        if (n < 3 || roleW == 0 || roleCovered / roleW < 0.4) {
            return new double[]{Double.NaN, n};
        }
        return new double[]{Math.sqrt(num / den), n};
    }

    private static List<String> explain(double[] t, double[] c, Map<Stat, Double> weights, boolean closest) {
        List<Stat> stats = new ArrayList<>();
        for (Stat s : weights.keySet()) {
            if (!Double.isNaN(t[s.ordinal()]) && !Double.isNaN(c[s.ordinal()])) {
                stats.add(s);
            }
        }
        Comparator<Stat> byGap = Comparator.comparingDouble(s -> weights.get(s)
                * Math.abs(t[s.ordinal()] - c[s.ordinal()]));
        stats.sort(closest ? byGap.thenComparing(s -> -weights.get(s)) : byGap.reversed());
        List<String> out = new ArrayList<>();
        for (Stat s : stats.subList(0, Math.min(3, stats.size()))) {
            double gap = c[s.ordinal()] - t[s.ordinal()];
            if (closest) {
                out.add(s.label());
            } else if (Math.abs(gap) >= 0.5) {
                out.add((gap > 0 ? "more " : "fewer ") + phrase(s.label())
                        + String.format(Locale.ROOT, " (%+.1f SD)", gap));
            }
        }
        return out;
    }

    /** "Key passes" → "key passes", but acronyms stay: "xA (expected assists)", "Non-penalty xG" → "non-penalty xG". */
    static String phrase(String label) {
        if (label.length() > 1 && Character.isUpperCase(label.charAt(1))) {
            return label;
        }
        return Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }

    private static Map<String, Double> asMap(double[] z, Map<Stat, Double> weights) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Stat s : weights.keySet()) {
            if (!Double.isNaN(z[s.ordinal()])) {
                m.put(s.key(), round3(z[s.ordinal()]));
            }
        }
        return m;
    }

    static Position parsePosition(String s) {
        try {
            return Position.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown position " + s + " (use GK, CB, FB, DM, CM, AM, W or ST)");
        }
    }

    private static String label(Integer season) {
        return season == null ? "…" : PlayerSeason.label(season);
    }

    private static double round1(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 10) / 10.0; // NaN = not measured, never 0
    }

    private static double round3(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 1000) / 1000.0; // NaN = not measured, never 0
    }

    // ------------------------------------------------------------------------------------------------ projection

    public record ProjectedStat(String stat, String label, double lastPer90, String lastSeason, double per90,
                                double low, double high, double z, double zLow, double zHigh) {
    }

    public record ProjectionView(String playerId, String name, String position, String season, int age,
                                 String method, List<String> basedOn, int comparables, List<ProjectedStat> stats,
                                 String note) {
    }

    /**
     * Next season for a player, per stat: projected per-90 value and 80% range, converted back from z using the most
     * recent cohort that measured the stat.
     */
    public ProjectionView projection(String playerId, String position) {
        Player p = player(playerId);
        List<PlayerSeason> eligible = data.seasonsOf(p.id()).stream()
                .filter(s -> s.minutes() >= Projector.MIN_SEASON_MINUTES).toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException(p.name() + " has no season with " + Projector.MIN_SEASON_MINUTES
                    + "+ minutes to project from");
        }
        Position pos = position != null ? parsePosition(position) : mainPosition(eligible.subList(
                Math.max(0, eligible.size() - 3), eligible.size()), p);
        Projector.Projection pr = projector.project(p.id(), pos, currentSeason, projector.defaultMethod());
        if (pr == null) {
            throw new IllegalStateException(p.name() + " played under " + Projector.MIN_SEASON_MINUTES
                    + " minutes in each of the last three seasons, so there is nothing recent to project from");
        }
        List<ProjectedStat> stats = new ArrayList<>();
        for (Stat st : Roles.positionStats(pos).keySet()) {
            int i = st.ordinal();
            if (Double.isNaN(pr.z()[i])) {
                continue;
            }
            StatScale sc = null;
            for (int y = currentSeason; y >= currentSeason - 3 && sc == null; y--) {
                sc = cohorts.cohort(pos, y).stat(st);
            }
            if (sc == null) {
                continue;
            }
            double last = Double.NaN;
            String lastLabel = null;
            for (PlayerSeason s : eligible) {
                double v = Cohorts.per90(s, st);
                if (!Double.isNaN(v)) {
                    last = v;
                    lastLabel = s.seasonLabel();
                }
            }
            double per90 = Math.max(0, sc.median + pr.z()[i] * sc.scale);
            double low = Double.isNaN(pr.low()[i]) ? Double.NaN : Math.max(0, sc.median + pr.low()[i] * sc.scale);
            double high = Double.isNaN(pr.high()[i]) ? Double.NaN : Math.max(0, sc.median + pr.high()[i] * sc.scale);
            stats.add(new ProjectedStat(st.key(), st.label(), round3(last), lastLabel, round3(per90), round3(low),
                    round3(high), round3(pr.z()[i]), round3(pr.low()[i]), round3(pr.high()[i])));
        }
        return new ProjectionView(p.id(), p.name(), pos.name(), PlayerSeason.label(currentSeason + 1), pr.age(),
                pr.method().name(), pr.basedOn().stream().map(PlayerSeason::label).toList(), pr.comparables(), stats,
                "Marcel: last three seasons weighted 5/4/3 and by minutes, shrunk toward the position median (R = "
                        + Math.round(projector.regressionMinutes()) + " minutes) plus the aging curve. The range is "
                        + "the 10th–90th percentile of the next-season changes of the " + pr.comparables()
                        + " most similar players at the same age; no range when too few of them had the stat measured "
                        + "in two consecutive seasons.");
    }

    /** Throws the API's 404 for unknown ids (used by callers that only have an id). */
    public Player player(String id) {
        if (!data.hasPlayer(id)) {
            throw new NoSuchElementException("no player with id " + id);
        }
        return data.player(id);
    }
}

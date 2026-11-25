package com.successor.engine.data;

import com.successor.engine.algo.BipartiteMatcher;
import com.successor.engine.algo.BipartiteMatcher.Edge;
import com.successor.engine.algo.BipartiteMatcher.Matching;
import com.successor.engine.model.League;
import com.successor.engine.model.Stat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Record linkage: decides which Transfermarkt player each Understat / FBref row belongs to.
 *
 * <ol>
 *   <li><b>Clubs</b>, per league-season: each source club is matched one-to-one to a Transfermarkt club by name
 *       similarity with {@link BipartiteMatcher#tiered} (thresholds 0.97 → 0.85 → 0.7 → 0.55 → 0.4, so exact names
 *       are fixed before ambiguous ones). If exactly one club is left
 *       on each side ("FC Cologne" / "1. FC Köln" share no letters), they are paired by elimination.</li>
 *   <li><b>Players</b>, per matched club-season: rows are matched one-to-one to that club's Transfermarkt players.
 *       Score = 0.55·name + 0.30·minutes agreement + 0.15·goals-and-assists agreement, halved when birth years
 *       disagree, and 0 below a name similarity of 0.5; tiers 0.95 → 0.85 → 0.7 → 0.55. One-to-one matching stops two rows claiming the same player (two
 *       "Danilo"s at one club) and lets a strong surname-plus-minutes match win over a weaker one.</li>
 *   <li><b>Consistency</b>: an Understat player id that links to different Transfermarkt players in different
 *       seasons keeps the majority link; its seasons that found no match inherit it. (FBref has no ids: its
 *       "name|birth year" keys can collide, so FBref rows are never consolidated.)</li>
 * </ol>
 */
public final class Linker {
    private Linker() {
    }

    public record ClubLink(League league, int season, String sourceClub, String tmClubKey, String tmClub,
                           double score, boolean byElimination) {
    }

    /** Linking outcome: {@code tmIdOf} maps each linked source row (by identity) to a Transfermarkt player id. */
    public record Result(Map<SourceRow, String> tmIdOf, Map<String, ClubLink> clubLinks, List<String> unmatchedClubs,
                         int rows, int linked, int reassignedByMajority, int conflicts, long matchingWork) {
        public String clubKeyFor(SourceRow r) {
            ClubLink c = clubLinks.get(sourceClubKey(r.source(), r.league(), r.season(), r.clubKey()));
            return c == null ? null : c.tmClubKey();
        }
    }

    static String clubKey(League l, int season, String club) {
        return l.name() + "|" + season + "|" + club;
    }

    /** Each source spells clubs its own way, so club links are kept per source. */
    static String sourceClubKey(com.successor.engine.model.Source src, League l, int season, String club) {
        return src.name() + "|" + clubKey(l, season, club);
    }

    public static Result link(List<SourceRow> tmRows, List<SourceRow> sourceRows) {
        // index Transfermarkt rows by league-season and club
        Map<String, Map<String, String>> tmClubs = new HashMap<>();        // league|season → clubKey → name
        Map<String, List<SourceRow>> tmByClub = new HashMap<>();           // league|season|clubKey → rows
        for (SourceRow r : tmRows) {
            tmClubs.computeIfAbsent(r.league().name() + "|" + r.season(), k -> new TreeMap<>())
                    .put(r.clubKey(), r.club());
            tmByClub.computeIfAbsent(clubKey(r.league(), r.season(), r.clubKey()), k -> new ArrayList<>()).add(r);
        }
        // grouped per source as well: Understat's "Borussia Dortmund" and FBref's "Dortmund" must each be matched
        // to the same Transfermarkt club, not compete for it
        Map<String, Map<String, List<SourceRow>>> srcByClub = new TreeMap<>(); // source|league|season → club → rows
        for (SourceRow r : sourceRows) {
            srcByClub.computeIfAbsent(r.source().name() + "|" + r.league().name() + "|" + r.season(),
                    k -> new TreeMap<>()).computeIfAbsent(r.clubKey(), k -> new ArrayList<>()).add(r);
        }

        Map<SourceRow, String> tmIdOf = new IdentityHashMap<>();
        Map<String, ClubLink> clubLinks = new LinkedHashMap<>();
        List<String> unmatchedClubs = new ArrayList<>();
        long work = 0;

        for (Map.Entry<String, Map<String, List<SourceRow>>> ls : srcByClub.entrySet()) {
            String[] parts = ls.getKey().split("\\|");
            com.successor.engine.model.Source source = com.successor.engine.model.Source.valueOf(parts[0]);
            League league = League.valueOf(parts[1]);
            int season = Integer.parseInt(parts[2]);
            Map<String, String> right = tmClubs.getOrDefault(league.name() + "|" + season, Map.of());
            List<String> leftClubs = new ArrayList<>(ls.getValue().keySet());
            List<String> rightKeys = new ArrayList<>(right.keySet());

            // 1. clubs
            List<Edge> edges = new ArrayList<>();
            for (int i = 0; i < leftClubs.size(); i++) {
                for (int j = 0; j < rightKeys.size(); j++) {
                    double s = Names.clubSimilarity(leftClubs.get(i), right.get(rightKeys.get(j)));
                    if (s >= 0.4) {
                        edges.add(new Edge(i, j, s));
                    }
                }
            }
            Matching m = BipartiteMatcher.tiered(leftClubs.size(), rightKeys.size(), edges, 0.97, 0.85, 0.7, 0.55, 0.4);
            work += m.work();
            int[] rightOf = m.rightOf().clone();
            boolean[] taken = new boolean[rightKeys.size()];
            for (int j : rightOf) {
                if (j >= 0) {
                    taken[j] = true;
                }
            }
            List<Integer> freeLeft = new ArrayList<>();
            List<Integer> freeRight = new ArrayList<>();
            for (int i = 0; i < rightOf.length; i++) {
                if (rightOf[i] < 0) {
                    freeLeft.add(i);
                }
            }
            for (int j = 0; j < taken.length; j++) {
                if (!taken[j]) {
                    freeRight.add(j);
                }
            }
            boolean elimination = freeLeft.size() == 1 && freeRight.size() == 1;
            if (elimination) {
                rightOf[freeLeft.getFirst()] = freeRight.getFirst();
            }
            for (int i = 0; i < leftClubs.size(); i++) {
                String src = leftClubs.get(i);
                if (rightOf[i] < 0) {
                    unmatchedClubs.add(league.label() + " " + season + " (" + source + "): " + src);
                    continue;
                }
                String tmKey = rightKeys.get(rightOf[i]);
                boolean byElim = elimination && i == freeLeft.getFirst();
                clubLinks.put(sourceClubKey(source, league, season, src), new ClubLink(league, season, src, tmKey, right.get(tmKey),
                        Names.clubSimilarity(src, right.get(tmKey)), byElim));

                // 2. players at this club-season
                List<SourceRow> l = ls.getValue().get(src);
                List<SourceRow> r = tmByClub.getOrDefault(clubKey(league, season, tmKey), List.of());
                List<Edge> pe = new ArrayList<>();
                for (int a = 0; a < l.size(); a++) {
                    for (int b = 0; b < r.size(); b++) {
                        double s = playerScore(l.get(a), r.get(b));
                        if (s >= 0.55) {
                            pe.add(new Edge(a, b, s));
                        }
                    }
                }
                Matching pm = BipartiteMatcher.tiered(l.size(), r.size(), pe, 0.95, 0.85, 0.7, 0.55);
                work += pm.work();
                for (int a = 0; a < l.size(); a++) {
                    if (pm.rightOf()[a] >= 0) {
                        tmIdOf.put(l.get(a), r.get(pm.rightOf()[a]).key());
                    }
                }
            }
        }

        // 3. one source id → one Transfermarkt id (majority over seasons)
        // only Understat has real player ids; FBref's "name|birth year" can be shared by two people (two Vitinhas
        // born in 2000), so each FBref row keeps its own club-season link
        Map<String, List<SourceRow>> bySourceId = new HashMap<>();
        for (SourceRow r : sourceRows) {
            if (r.source() == com.successor.engine.model.Source.UNDERSTAT) {
                bySourceId.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r);
            }
        }
        int reassigned = 0;
        int conflicts = 0;
        for (List<SourceRow> rows : bySourceId.values()) {
            Map<String, Integer> votes = new HashMap<>();
            for (SourceRow r : rows) {
                String id = tmIdOf.get(r);
                if (id != null) {
                    votes.merge(id, 1, Integer::sum);
                }
            }
            if (votes.isEmpty()) {
                continue;
            }
            // most votes; ties go to the smaller id so the result is deterministic
            Comparator<Map.Entry<String, Integer>> byVotes = Map.Entry.comparingByValue();
            Comparator<Map.Entry<String, Integer>> byId = Map.Entry.comparingByKey();
            String winner = votes.entrySet().stream().max(byVotes.thenComparing(byId.reversed()))
                    .orElseThrow().getKey();
            if (votes.size() > 1) {
                conflicts++;
            }
            for (SourceRow r : rows) {
                String before = tmIdOf.put(r, winner);
                if (!winner.equals(before)) {
                    reassigned++;
                }
            }
        }
        return new Result(tmIdOf, clubLinks, unmatchedClubs, sourceRows.size(), tmIdOf.size(), reassigned, conflicts,
                work);
    }

    /** Below this name similarity two rows are never linked, whatever their numbers say. */
    static final double MIN_NAME = 0.5;

    /** How likely a source row and a Transfermarkt row (same club-season) are the same player, in [0, 1]. */
    static double playerScore(SourceRow src, SourceRow tm) {
        double name = Names.personSimilarity(src.name(), tm.name());
        if (name < MIN_NAME) {
            return 0; // matching minutes alone must never link two differently named players
        }
        double m1 = src.stats()[Stat.MINUTES.ordinal()];
        double m2 = tm.stats()[Stat.MINUTES.ordinal()];
        double minutes = Double.isNaN(m1) || Double.isNaN(m2) ? 0.5
                : 1 - Math.min(1, Math.abs(m1 - m2) / Math.max(90, Math.max(m1, m2)));
        double ga = agreement(src, tm, Stat.GOALS) * 0.5 + agreement(src, tm, Stat.ASSISTS) * 0.5;
        double score = 0.55 * name + 0.30 * minutes + 0.15 * ga;
        if (src.birthYear() > 0 && tm.birthYear() > 0 && Math.abs(src.birthYear() - tm.birthYear()) > 1) {
            score *= 0.5;
        }
        return score;
    }

    private static double agreement(SourceRow a, SourceRow b, Stat s) {
        double x = a.stats()[s.ordinal()];
        double y = b.stats()[s.ordinal()];
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return 0.5;
        }
        return 1 - Math.min(1, Math.abs(x - y) / (Math.max(x, y) + 2));
    }
}

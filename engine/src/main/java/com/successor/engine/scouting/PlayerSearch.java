package com.successor.engine.scouting;

import com.successor.engine.algo.Selection;
import com.successor.engine.algo.Trie;
import com.successor.engine.algo.Work;
import com.successor.engine.data.Dataset;
import com.successor.engine.data.Names;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Type-ahead player search. Every word of every name and alias is put into a {@link Trie} after normalisation
 * (lower case, accents removed), so "ode" finds Ødegaard, "bru" finds De Bruyne and "mathis" finds Rayan Cherki
 * (listed as Mathis Cherki by Understat). A query of several words keeps the players matching every word
 * ("kev bru"). Matches are ranked whole-name match first, then exact word matches ("rodri" finds Rodri before
 * Rodríguez), then career minutes, using quickselect top-k.
 */
public final class PlayerSearch {

    /** One search hit. {@code matchedAlias} is set when the query matched another spelling of the name. */
    public record Hit(String id, String name, String matchedAlias, String position, String club, String lastSeason,
                      int birthYear, long careerMinutes) {
    }

    /** Hits plus the work done, for the "how it works" panel and benchmarks. */
    public record Result(List<Hit> hits, int matched, long trieWork, long rankWork) {
    }

    private final Trie trie = new Trie();
    private final Player[] players;
    private final double[] careerMinutes;
    private final String[] club;
    private final String[] lastSeason;
    private final String[][] spellings;
    private final String[][][] spellingTokens; // normalised once here, never per query

    public PlayerSearch(Dataset d) {
        players = d.players().toArray(new Player[0]);
        careerMinutes = new double[players.length];
        club = new String[players.length];
        lastSeason = new String[players.length];
        spellings = new String[players.length][];
        spellingTokens = new String[players.length][][];
        for (int i = 0; i < players.length; i++) {
            Player p = players[i];
            List<PlayerSeason> seasons = d.seasonsOf(p.id());
            for (PlayerSeason s : seasons) {
                careerMinutes[i] += s.minutes();
            }
            if (!seasons.isEmpty()) {
                PlayerSeason last = seasons.getLast();
                club[i] = last.club();
                lastSeason[i] = last.seasonLabel();
            }
            List<String> names = new ArrayList<>();
            names.add(p.name());
            names.addAll(p.aliases());
            spellings[i] = names.toArray(new String[0]);
            spellingTokens[i] = new String[names.size()][];
            for (int k = 0; k < names.size(); k++) {
                spellingTokens[i][k] = Names.tokens(names.get(k)).toArray(new String[0]);
                for (String word : spellingTokens[i][k]) {
                    trie.insert(word, i);
                }
            }
        }
    }

    public Result search(String query, int limit) {
        if (query == null || Names.normalize(query).isEmpty()) {
            throw new IllegalArgumentException("type at least one letter or digit to search");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100, got " + limit);
        }
        List<String> words = Names.tokens(query);
        Work trieWork = Work.counter();
        int[] hits = null;
        for (String w : words) {
            int[] ids = trie.prefix(w, trieWork);
            hits = hits == null ? ids : intersect(hits, ids);
            if (hits.length == 0) {
                break;
            }
        }
        int[] matched = hits == null ? new int[0] : hits;
        // rank: whole-name matches, then exact word matches ("rodri" = Rodri before Rodríguez), then career minutes;
        // top-k by quickselect on the negated score
        double[] keys = new double[matched.length];
        String normQuery = String.join(" ", words);
        for (int i = 0; i < matched.length; i++) {
            keys[i] = -(careerMinutes[matched[i]] + exactness(matched[i], words, normQuery));
        }
        Work rankWork = Work.counter();
        int[] top = Selection.topK(keys, limit, new Random(matched.length), rankWork);
        List<Hit> out = new ArrayList<>(top.length);
        for (int t : top) {
            int i = matched[t];
            Player p = players[i];
            out.add(new Hit(p.id(), p.name(), aliasMatched(i, words), p.position() == null ? null : p.position().name(),
                    club[i], lastSeason[i], p.birthYear(), Math.round(careerMinutes[i])));
        }
        return new Result(out, matched.length, trieWork.get(), rankWork.get());
    }

    /** Baseline for benchmarks: check every player's words against every query word. */
    public int linearScanCount(String query, Work work) {
        List<String> words = Names.tokens(query);
        int count = 0;
        for (int i = 0; i < players.length; i++) {
            boolean all = true;
            for (String w : words) {
                boolean any = false;
                for (String name : spellings[i]) {
                    for (String token : Names.tokens(name)) {
                        work.inc();
                        if (token.startsWith(w)) {
                            any = true;
                            break;
                        }
                    }
                    if (any) {
                        break;
                    }
                }
                if (!any) {
                    all = false;
                    break;
                }
            }
            if (all) {
                count++;
            }
        }
        return count;
    }

    public int size() {
        return players.length;
    }

    public Trie trie() {
        return trie;
    }

    /** 10^7 if a spelling equals the query, plus 10^6 per query word that equals a whole word of a spelling. */
    private double exactness(int i, List<String> words, String normQuery) {
        double best = 0;
        for (String[] tokens : spellingTokens[i]) {
            double score = String.join(" ", tokens).equals(normQuery) ? 1e7 : 0;
            for (String w : words) {
                for (String t : tokens) {
                    if (t.equals(w)) {
                        score += 1e6;
                        break;
                    }
                }
            }
            best = Math.max(best, score);
        }
        return best;
    }

    /** The alias whose words matched when the main name does not match every query word, else null. */
    private String aliasMatched(int i, List<String> words) {
        if (matchesAll(spellings[i][0], words)) {
            return null;
        }
        for (int k = 1; k < spellings[i].length; k++) {
            if (matchesAll(spellings[i][k], words)) {
                return spellings[i][k];
            }
        }
        return spellings[i].length > 1 ? spellings[i][1] : null;
    }

    private static boolean matchesAll(String name, List<String> words) {
        List<String> tokens = Names.tokens(name);
        for (String w : words) {
            if (tokens.stream().noneMatch(t -> t.startsWith(w))) {
                return false;
            }
        }
        return true;
    }

    private static int[] intersect(int[] a, int[] b) {
        int[] x = a.clone();
        int[] y = b.clone();
        Arrays.sort(x);
        Arrays.sort(y);
        int[] out = new int[Math.min(x.length, y.length)];
        int i = 0;
        int j = 0;
        int n = 0;
        while (i < x.length && j < y.length) {
            if (x[i] < y[j]) {
                i++;
            } else if (x[i] > y[j]) {
                j++;
            } else {
                out[n++] = x[i];
                i++;
                j++;
            }
        }
        return Arrays.copyOf(out, n);
    }
}

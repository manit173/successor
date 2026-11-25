package com.successor.engine.data;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Name normalisation and similarity for linking records across sources ("Mathis Cherki" ↔ "Rayan Cherki",
 * "Borussia M.Gladbach" ↔ "Borussia Mönchengladbach").
 */
public final class Names {
    private Names() {
    }

    /** Club-name words that say nothing about which club it is. */
    private static final Set<String> CLUB_STOPWORDS = Set.of("fc", "cf", "ac", "afc", "sc", "ssc", "us", "as", "ss",
            "sv", "vfl", "vfb", "tsg", "rc", "rcd", "ca", "cd", "ud", "sd", "ogc", "losc", "hsc", "aj", "acf", "bc",
            "cfc", "club", "de", "del", "la", "le", "calcio", "football", "fussballclub", "fsv", "spa", "s", "p", "a",
            "associazione", "sportiva", "societa", "balompie", "sco", "29", "bsc", "and");

    /** Spellings that share no letters with the other source's spelling. */
    private static final Map<String, String> CLUB_ALIASES = Map.of(
            "cologne", "koln", "utd", "united", "gladbach", "monchengladbach",
            "nott", "nottingham", "wolves", "wolverhampton", "rasenballsport", "rb", "inter", "internazionale");

    /** Lower case, accents removed (é→e, ø→o, ß→ss, ł→l, …), punctuation turned into spaces, spaces collapsed. */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.toLowerCase(Locale.ROOT)
                .replace("ß", "ss").replace("ø", "o").replace("æ", "ae").replace("œ", "oe").replace("ł", "l")
                .replace("đ", "d").replace("ð", "d").replace("þ", "th").replace("ı", "i");
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.replaceAll("[^a-z0-9]+", " ").trim();
        return t.replaceAll(" +", " ");
    }

    public static List<String> tokens(String s) {
        String n = normalize(s);
        return n.isEmpty() ? List.of() : List.of(n.split(" "));
    }

    /** Club tokens without stop words and digits, with aliases applied. */
    public static List<String> clubTokens(String club) {
        List<String> out = new ArrayList<>();
        for (String t : tokens(club)) {
            if (CLUB_STOPWORDS.contains(t) || t.chars().allMatch(Character::isDigit)) {
                continue;
            }
            out.add(CLUB_ALIASES.getOrDefault(t, t));
        }
        if (out.isEmpty()) { // e.g. "AC" alone: keep what there was
            out.addAll(tokens(club));
        }
        return out;
    }

    /** Similarity of two club names in [0, 1]. */
    public static double clubSimilarity(String a, String b) {
        List<String> ta = clubTokens(a);
        List<String> tb = clubTokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        double hits = fuzzyOverlap(ta, tb);
        // containment ("Inter" ⊂ "Inter Milan") blended with Dice so that a full match beats a partial one
        double overlap = 0.8 * hits / Math.min(ta.size(), tb.size()) + 0.2 * 2 * hits / (ta.size() + tb.size());
        double whole = jaroWinkler(String.join(" ", ta), String.join(" ", tb));
        return Math.min(1, Math.max(overlap, whole * 0.85));
    }

    /** Similarity of two person names in [0, 1]. Same surname scores ≥ 0.8 even when first names differ. */
    public static double personSimilarity(String a, String b) {
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        String na = String.join(" ", ta);
        String nb = String.join(" ", tb);
        if (na.equals(nb)) {
            return 1;
        }
        double overlap = fuzzyOverlap(ta, tb) / Math.min(ta.size(), tb.size());
        double score = overlap * (ta.size() == tb.size() || Math.min(ta.size(), tb.size()) > 1 ? 1 : 0.9);
        // whole-string similarity only counts when it is very close ("Dusan Vlahovic" / "Dušan Vlahović" after
        // normalising is exact anyway); loose letter overlap ("paik seung ho" / "xavi quintilla") must not count
        double whole = jaroWinkler(na, nb);
        if (whole >= 0.9) {
            score = Math.max(score, whole - 0.05);
        }
        if (tokenMatch(ta.getLast(), tb.getLast())) {
            score = Math.max(score, 0.8);
        }
        return Math.min(1, score);
    }

    /** Matched tokens, exact matches first (1 each), then fuzzy ones (0.85 each), each token used once. */
    private static double fuzzyOverlap(List<String> ta, List<String> tb) {
        boolean[] usedA = new boolean[ta.size()];
        boolean[] usedB = new boolean[tb.size()];
        double hits = 0;
        for (int i = 0; i < ta.size(); i++) {
            for (int j = 0; j < tb.size(); j++) {
                if (!usedB[j] && ta.get(i).equals(tb.get(j))) {
                    usedA[i] = true;
                    usedB[j] = true;
                    hits += 1;
                    break;
                }
            }
        }
        for (int i = 0; i < ta.size(); i++) {
            if (usedA[i]) {
                continue;
            }
            for (int j = 0; j < tb.size(); j++) {
                if (!usedB[j] && tokenMatch(ta.get(i), tb.get(j))) {
                    usedB[j] = true;
                    hits += 0.85;
                    break;
                }
            }
        }
        return hits;
    }

    /**
     * Equal, one a prefix of the other with ≥ 4 letters ("brest"/"brestois"), or Jaro–Winkler ≥ 0.9. Suffixes are
     * deliberately not enough: "real" ends "villarreal" but they are different clubs.
     */
    static boolean tokenMatch(String x, String y) {
        if (x.equals(y)) {
            return true;
        }
        String shorter = x.length() <= y.length() ? x : y;
        String longer = shorter == x ? y : x;
        if (shorter.length() >= 4 && longer.startsWith(shorter)) {
            return true;
        }
        return shorter.length() >= 4 && jaroWinkler(x, y) >= 0.9;
    }

    /** Jaro–Winkler similarity in [0, 1]. */
    public static double jaroWinkler(String s, String t) {
        if (s.equals(t)) {
            return 1;
        }
        int ls = s.length();
        int lt = t.length();
        if (ls == 0 || lt == 0) {
            return 0;
        }
        int window = Math.max(0, Math.max(ls, lt) / 2 - 1);
        boolean[] ms = new boolean[ls];
        boolean[] mt = new boolean[lt];
        int matches = 0;
        for (int i = 0; i < ls; i++) {
            int lo = Math.max(0, i - window);
            int hi = Math.min(lt - 1, i + window);
            for (int j = lo; j <= hi; j++) {
                if (!mt[j] && s.charAt(i) == t.charAt(j)) {
                    ms[i] = true;
                    mt[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < ls; i++) {
            if (ms[i]) {
                while (!mt[k]) {
                    k++;
                }
                if (s.charAt(i) != t.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / ls + m / lt + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(ls, lt)) && s.charAt(prefix) == t.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}

package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Readers for the earlier FBref "Big 5" seasons, each published by a different person in a different shape:
 * <ul>
 *   <li><b>2021/22</b> (Kaggle vivovinco, CC BY 4.0): one semicolon-separated Latin-1 file in FBref's StatsBomb-era
 *       naming, with <em>per-90</em> values that are turned back into season totals. No xG columns (Understat's xG
 *       covers that season).</li>
 *   <li><b>2022/23</b> (Kaggle ameyaranade, CC0): nine FBref tables, joined on player, club and birth year. Column names
 *       repeat inside a table ("Tkl" twice), so columns are taken by first occurrence.</li>
 *   <li><b>2023/24</b> (Kaggle anisguechtouli): normalised tables keyed by a player id, 17 leagues of which the five are
 *       kept. Birth year is derived from the age at the start of the season (±1 year, which linking tolerates).</li>
 * </ul>
 */
public final class FbrefSeasons {
    private FbrefSeasons() {
    }

    // ------------------------------------------------------------------------------------------------ 2021/22

    private static final Map<String, Stat> COLS_2122 = Map.ofEntries(
            entry("Goals", Stat.GOALS), entry("Assists", Stat.ASSISTS), entry("Shots", Stat.SHOTS),
            entry("SoT", Stat.SHOTS_ON_TARGET), entry("PasTotCmp", Stat.PASSES_COMPLETED),
            entry("PasTotAtt", Stat.PASSES_ATTEMPTED), entry("PasAss", Stat.KEY_PASSES),
            entry("Pas3rd", Stat.PASSES_FINAL_THIRD), entry("PPA", Stat.PASSES_INTO_BOX),
            entry("CrsPA", Stat.CROSSES_INTO_BOX), entry("PasProg", Stat.PROG_PASSES), entry("SCA", Stat.SCA),
            entry("GCA", Stat.GCA), entry("Tkl", Stat.TACKLES), entry("TklWon", Stat.TACKLES_WON),
            entry("Blocks", Stat.BLOCKS), entry("Int", Stat.INTERCEPTIONS), entry("Clr", Stat.CLEARANCES),
            entry("Touches", Stat.TOUCHES), entry("TouAttPen", Stat.TOUCHES_ATT_BOX), entry("DriSucc", Stat.TAKE_ONS),
            entry("CarProg", Stat.PROG_CARRIES), entry("CPA", Stat.CARRIES_INTO_BOX),
            entry("CarMis", Stat.MISCONTROLS), entry("CarDis", Stat.DISPOSSESSED), entry("RecProg", Stat.PROG_RECEPTIONS),
            entry("CrdY", Stat.YELLOW_CARDS), entry("CrdR", Stat.RED_CARDS), entry("Fld", Stat.FOULS_DRAWN),
            entry("Crs", Stat.CROSSES), entry("Recov", Stat.RECOVERIES), entry("AerWon", Stat.AERIALS_WON));

    /** 2021/22: per-90 values × minutes / 90 = season totals. */
    public static List<SourceRow> read2122(Path csv) {
        List<SourceRow> rows = new ArrayList<>();
        Table t = Table.read(csv, StandardCharsets.ISO_8859_1, ';');
        for (Map<String, String> r : t.rows) {
            League league = League.fromLabel(r.getOrDefault("Comp", ""));
            String name = r.getOrDefault("Player", "").trim();
            double minutes = num(r.get("Min"));
            if (league == null || name.isEmpty() || Double.isNaN(minutes)) {
                continue;
            }
            double nineties = minutes / 90.0;
            double[] s = PlayerSeason.emptyStats();
            s[Stat.MINUTES.ordinal()] = minutes;
            s[Stat.GAMES.ordinal()] = num(r.get("MP"));
            s[Stat.STARTS.ordinal()] = num(r.get("Starts"));
            for (Map.Entry<String, Stat> e : COLS_2122.entrySet()) {
                double per90 = num(r.get(e.getKey()));
                if (!Double.isNaN(per90)) {
                    s[e.getValue().ordinal()] = Math.round(per90 * nineties * 10) / 10.0;
                }
            }
            double pk = num(r.get("ShoPK"));
            if (!Double.isNaN(pk) && !Double.isNaN(s[Stat.GOALS.ordinal()])) {
                s[Stat.NP_GOALS.ordinal()] = Math.max(0, Math.round((num(r.get("Goals")) - pk) * nineties * 10) / 10.0);
            }
            int born = (int) zero(num(r.get("Born")));
            rows.add(new SourceRow(Source.FBREF, name + "|" + born, name, born, 2021, league, r.get("Squad"),
                    r.get("Squad"), r.getOrDefault("Pos", ""), s));
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------------ 2022/23

    /** Table file → (column → stat), each column by its first occurrence in that table. */
    private static final Map<String, Map<String, Stat>> TABLES_2223 = new LinkedHashMap<>();

    static {
        TABLES_2223.put("statsStats", Map.ofEntries(entry("MP", Stat.GAMES), entry("Starts", Stat.STARTS),
                entry("Min", Stat.MINUTES), entry("Gls", Stat.GOALS), entry("Ast", Stat.ASSISTS),
                entry("G-PK", Stat.NP_GOALS), entry("CrdY", Stat.YELLOW_CARDS), entry("CrdR", Stat.RED_CARDS),
                entry("xG", Stat.XG), entry("npxG", Stat.NPXG), entry("xAG", Stat.XA), entry("PrgC", Stat.PROG_CARRIES),
                entry("PrgP", Stat.PROG_PASSES), entry("PrgR", Stat.PROG_RECEPTIONS)));
        TABLES_2223.put("shootingStats", Map.of("Sh", Stat.SHOTS, "SoT", Stat.SHOTS_ON_TARGET));
        TABLES_2223.put("passingStats", Map.of("Cmp", Stat.PASSES_COMPLETED, "Att", Stat.PASSES_ATTEMPTED,
                "KP", Stat.KEY_PASSES, "1/3", Stat.PASSES_FINAL_THIRD, "PPA", Stat.PASSES_INTO_BOX,
                "CrsPA", Stat.CROSSES_INTO_BOX));
        TABLES_2223.put("gcaStats", Map.of("SCA", Stat.SCA, "GCA", Stat.GCA));
        TABLES_2223.put("defenseStats", Map.of("Tkl", Stat.TACKLES, "TklW", Stat.TACKLES_WON, "Blocks", Stat.BLOCKS,
                "Int", Stat.INTERCEPTIONS, "Clr", Stat.CLEARANCES));
        TABLES_2223.put("possessionStats", Map.of("Touches", Stat.TOUCHES, "Att Pen", Stat.TOUCHES_ATT_BOX,
                "Succ", Stat.TAKE_ONS, "CPA", Stat.CARRIES_INTO_BOX, "Mis", Stat.MISCONTROLS, "Dis", Stat.DISPOSSESSED));
        TABLES_2223.put("miscStats", Map.of("Fld", Stat.FOULS_DRAWN, "Crs", Stat.CROSSES, "Recov", Stat.RECOVERIES,
                "Won", Stat.AERIALS_WON));
        TABLES_2223.put("keepersStats", Map.of("GA", Stat.GOALS_AGAINST, "SoTA", Stat.SHOTS_ON_TARGET_AGAINST,
                "Saves", Stat.SAVES, "CS", Stat.CLEAN_SHEETS));
        TABLES_2223.put("keepersadvStats", Map.of("PSxG", Stat.PSXG));
    }

    public static List<SourceRow> read2223(Path dir) {
        Map<String, double[]> stats = new LinkedHashMap<>();
        Map<String, Map<String, String>> ident = new HashMap<>();
        for (Map.Entry<String, Map<String, Stat>> table : TABLES_2223.entrySet()) {
            Path f = dir.resolve("Big5-" + table.getKey() + "22-23.csv");
            if (!Files.isRegularFile(f)) {
                throw new IllegalArgumentException("missing 2022/23 table " + f);
            }
            Table t = Table.read(f, StandardCharsets.UTF_8, ',');
            for (Map<String, String> r : t.rows) {
                String key = r.get("Player") + "|" + r.get("Squad") + "|" + r.get("Born");
                double[] s = stats.computeIfAbsent(key, k -> PlayerSeason.emptyStats());
                ident.putIfAbsent(key, r);
                for (Map.Entry<String, Stat> e : table.getValue().entrySet()) {
                    double v = num(r.get(e.getKey()));
                    if (!Double.isNaN(v)) {
                        s[e.getValue().ordinal()] = v;
                    }
                }
            }
        }
        List<SourceRow> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> e : stats.entrySet()) {
            Map<String, String> r = ident.get(e.getKey());
            League league = League.fromFbref(r.getOrDefault("Comp", ""));
            String name = r.getOrDefault("Player", "").trim();
            if (league == null || name.isEmpty() || Double.isNaN(e.getValue()[Stat.MINUTES.ordinal()])) {
                continue;
            }
            int born = (int) zero(num(r.get("Born")));
            rows.add(new SourceRow(Source.FBREF, name + "|" + born, name, born, 2022, league, r.get("Squad"),
                    r.get("Squad"), r.getOrDefault("Pos", ""), e.getValue()));
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------------ 2023/24

    private record Col(String table, String column, Stat stat, boolean per90) {
    }

    private static final List<Col> COLS_2324 = List.of(
            new Col("Playing_Time", "games", Stat.GAMES, false), new Col("Playing_Time", "minutes", Stat.MINUTES, false),
            new Col("Playing_Time", "game_starts", Stat.STARTS, false), new Col("Shooting", "goals", Stat.GOALS, false),
            new Col("Shooting", "SOTs", Stat.SHOTS_ON_TARGET, false), new Col("Shooting", "shots_per90", Stat.SHOTS, true),
            new Col("Shooting", "xg", Stat.XG, false), new Col("Shooting", "npxg", Stat.NPXG, false),
            new Col("Passing", "passes_comp", Stat.PASSES_COMPLETED, false),
            new Col("Passing", "passes", Stat.PASSES_ATTEMPTED, false), new Col("Passing", "assists", Stat.ASSISTS, false),
            new Col("Passing", "xAG", Stat.XA, false), new Col("Passing", "KP", Stat.KEY_PASSES, false),
            new Col("Passing", "P_into_fin3rd", Stat.PASSES_FINAL_THIRD, false),
            new Col("Passing", "P_into_PA", Stat.PASSES_INTO_BOX, false),
            new Col("Passing", "C_into_PA", Stat.CROSSES_INTO_BOX, false),
            new Col("Passing", "prog_passes", Stat.PROG_PASSES, false), new Col("GSC", "sca", Stat.SCA, false),
            new Col("GSC", "gca", Stat.GCA, false), new Col("Defense", "p_tackled", Stat.TACKLES, false),
            new Col("Defense", "tklw", Stat.TACKLES_WON, false), new Col("Defense", "blocks", Stat.BLOCKS, false),
            new Col("Defense", "intr", Stat.INTERCEPTIONS, false), new Col("Defense", "clr", Stat.CLEARANCES, false),
            new Col("Possession", "touches", Stat.TOUCHES, false),
            new Col("Possession", "touches_apa", Stat.TOUCHES_ATT_BOX, false),
            new Col("Possession", "TO_won", Stat.TAKE_ONS, false),
            new Col("Possession", "prog_carries", Stat.PROG_CARRIES, false),
            new Col("Possession", "carries_into_pa", Stat.CARRIES_INTO_BOX, false),
            new Col("Possession", "miscontrols", Stat.MISCONTROLS, false),
            new Col("Possession", "dispossessed", Stat.DISPOSSESSED, false),
            new Col("Possession", "prog_passes_rec", Stat.PROG_RECEPTIONS, false),
            new Col("Misc", "Y", Stat.YELLOW_CARDS, false), new Col("Misc", "R", Stat.RED_CARDS, false),
            new Col("Misc", "fouled", Stat.FOULS_DRAWN, false), new Col("Misc", "crosses", Stat.CROSSES, false),
            new Col("Misc", "ball_recoveries", Stat.RECOVERIES, false),
            new Col("Misc", "aerials_won", Stat.AERIALS_WON, false),
            new Col("Goalkeeping", "GA", Stat.GOALS_AGAINST, false),
            new Col("Goalkeeping", "shotsOT_a", Stat.SHOTS_ON_TARGET_AGAINST, false),
            new Col("Goalkeeping", "saves", Stat.SAVES, false), new Col("Goalkeeping", "CS", Stat.CLEAN_SHEETS, false),
            new Col("Goalkeeping_adv", "psxg", Stat.PSXG, false));

    public static List<SourceRow> read2324(Path dir) {
        Map<String, String> leagueOfId = new HashMap<>();
        for (Map<String, String> r : Table.read(dir.resolve("t_leagues.csv"), StandardCharsets.UTF_8, ',').rows) {
            leagueOfId.put(r.get("league_id"), r.get("league"));
        }
        Map<String, String[]> club = new HashMap<>(); // id → {name, league label}
        for (Map<String, String> r : Table.read(dir.resolve("t_club.csv"), StandardCharsets.UTF_8, ',').rows) {
            club.put(r.get("club_id"), new String[]{r.get("club"), leagueOfId.get(r.get("league_id"))});
        }
        Map<String, String> positionOfId = new HashMap<>();
        for (Map<String, String> r : Table.read(dir.resolve("t_position.csv"), StandardCharsets.UTF_8, ',').rows) {
            positionOfId.put(r.get("pos_id"), r.get("position"));
        }
        Map<String, Map<String, String>> players = new LinkedHashMap<>();
        for (Map<String, String> r : Table.read(dir.resolve("t_player.csv"), StandardCharsets.UTF_8, ',').rows) {
            players.put(r.get("player_id"), r);
        }
        Map<String, double[]> stats = new HashMap<>();
        Map<String, Map<String, Map<String, String>>> tables = new HashMap<>();
        for (Col c : COLS_2324) {
            Map<String, Map<String, String>> t = tables.computeIfAbsent(c.table(), name -> {
                Map<String, Map<String, String>> byId = new HashMap<>();
                for (Map<String, String> r : Table.read(dir.resolve(name + ".csv"), StandardCharsets.UTF_8, ',').rows) {
                    byId.put(r.get("player_id"), r);
                }
                return byId;
            });
            for (Map.Entry<String, Map<String, String>> e : t.entrySet()) {
                double v = num(e.getValue().get(c.column()));
                if (!Double.isNaN(v)) {
                    stats.computeIfAbsent(e.getKey(), k -> PlayerSeason.emptyStats())[c.stat().ordinal()] = v;
                }
            }
        }
        List<SourceRow> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : players.entrySet()) {
            Map<String, String> p = e.getValue();
            String[] c = club.get(p.get("club_id"));
            League league = c == null || c[1] == null ? null : League.fromLabel(c[1]);
            double[] s = stats.get(e.getKey());
            if (league == null || s == null || Double.isNaN(s[Stat.MINUTES.ordinal()])) {
                continue;
            }
            // shots are only published per 90 here
            double m = s[Stat.MINUTES.ordinal()];
            if (!Double.isNaN(s[Stat.SHOTS.ordinal()])) {
                s[Stat.SHOTS.ordinal()] = Math.round(s[Stat.SHOTS.ordinal()] * m / 90 * 10) / 10.0;
            }
            if (!Double.isNaN(s[Stat.GOALS.ordinal()])) {
                Map<String, String> sh = tables.get("Shooting").get(e.getKey());
                s[Stat.NP_GOALS.ordinal()] = s[Stat.GOALS.ordinal()] - zero(num(sh == null ? null : sh.get("pens_scored")));
            }
            String pos = positionOfId.getOrDefault(p.get("position_id"), "");
            if (!pos.equals("GK")) {
                for (Stat g : new Stat[]{Stat.GOALS_AGAINST, Stat.SHOTS_ON_TARGET_AGAINST, Stat.SAVES,
                        Stat.CLEAN_SHEETS, Stat.PSXG}) {
                    s[g.ordinal()] = Double.NaN;
                }
            }
            double age = num(p.get("age"));
            // ages are as at the start of 2023/24 (Aaron Wan-Bissaka, born Nov 1997, is listed as 25), so the birth
            // year is 2023 − age, give or take one year, which linking tolerates
            int born = Double.isNaN(age) ? 0 : 2023 - (int) age;
            String name = p.get("player").trim();
            rows.add(new SourceRow(Source.FBREF, name + "|" + born, name, born, 2023, league, c[0], c[0], pos, s));
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A delimited file read into maps; when a header repeats, the first occurrence wins. */
    private static final class Table {
        final List<Map<String, String>> rows = new ArrayList<>();

        static Table read(Path f, Charset cs, char delim) {
            Table t = new Table();
            try (BufferedReader r = Files.newBufferedReader(f, cs)) {
                String header = r.readLine();
                if (header == null) {
                    return t;
                }
                List<String> h = split(header.replace("﻿", ""), delim);
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> cells = split(line, delim);
                    Map<String, String> m = new HashMap<>();
                    for (int i = 0; i < h.size() && i < cells.size(); i++) {
                        m.putIfAbsent(h.get(i), cells.get(i));
                    }
                    t.rows.add(m);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + f, e);
            }
            return t;
        }

        private static List<String> split(String line, char delim) {
            List<String> out = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"' && cell.isEmpty()) {
                    quoted = true;
                } else if (c == delim) {
                    out.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            out.add(cell.toString());
            return out;
        }
    }

    static double num(String s) {
        if (s == null) {
            return Double.NaN;
        }
        String t = s.trim().replace(",", "");
        if (t.isEmpty()) {
            return Double.NaN;
        }
        if (t.startsWith("+")) {
            t = t.substring(1);
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double zero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }
}

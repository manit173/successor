package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Two Kaggle exports made with the soccerdata library (leagues spelt "ENG-Premier League", seasons "1718"):
 * <ul>
 *   <li><b>FBref archive 2017/18–2024/25</b> (Kaggle jacksonjohannessen, MIT label; FBref / Opta content): standard,
 *       shooting, passing, shot-creation, defensive and goalkeeping tables in <em>per-90</em> form for the Big 5,
 *       Eredivisie, Primeira Liga, Belgian Pro League and Süper Lig. Only players matched to the FIFA video game are
 *       included (mostly regulars). Per-90 values are turned back into season totals; counts (goals, cards, …) are
 *       rounded to whole numbers, which is exact while the per-90 rounding error × minutes / 90 stays under ½.</li>
 *   <li><b>Big 5 2025/26</b> (Kaggle chuongtrinh, MIT label; FBref, Understat and Sofascore content): FBref's basic
 *       table, Understat's xG family and Sofascore's defending, carrying and passing counts. Where several sources
 *       measure one thing, the one used in earlier seasons is kept (Understat key passes, FBref interceptions).</li>
 * </ul>
 */
public final class SoccerdataSets {
    private SoccerdataSets() {
    }

    private static final Map<String, League> LEAGUES = Map.of(
            "ENG-Premier League", League.PREMIER_LEAGUE, "ESP-La Liga", League.LA_LIGA,
            "GER-Bundesliga", League.BUNDESLIGA, "ITA-Serie A", League.SERIE_A, "FRA-Ligue 1", League.LIGUE_1,
            "NED-Eredivisie", League.EREDIVISIE, "POR-Primeira Liga", League.PRIMEIRA_LIGA,
            "BEL-Jupiler Pro League", League.BELGIAN_PRO_LEAGUE, "TUR-Super Lig", League.SUPER_LIG);

    static League league(String soccerdata) {
        return soccerdata == null ? null : LEAGUES.get(soccerdata.trim());
    }

    /** "1718" → 2017 (the year the season starts); −1 if unreadable. */
    static int season(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() != 4 || !t.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        int a = Integer.parseInt(t.substring(0, 2));
        int b = Integer.parseInt(t.substring(2));
        return b == (a + 1) % 100 ? 2000 + a : -1; // two consecutive years, "1718"; "2017" is not a season code
    }

    private static final Stat[] COUNTS = {Stat.GOALS, Stat.NP_GOALS, Stat.ASSISTS, Stat.YELLOW_CARDS, Stat.RED_CARDS,
            Stat.CLEAN_SHEETS};
    private static final Stat[] KEEPING = {Stat.GOALS_AGAINST, Stat.SHOTS_ON_TARGET_AGAINST, Stat.SAVES,
            Stat.CLEAN_SHEETS, Stat.PSXG};

    // ------------------------------------------------------------------------------------------------ FBref archive

    private static final Map<String, Stat> ARCHIVE_PER90 = Map.ofEntries(
            entry("Per 90 Minutes_Gls", Stat.GOALS), entry("Per 90 Minutes_G-PK", Stat.NP_GOALS),
            entry("Per 90 Minutes_Ast", Stat.ASSISTS), entry("Per 90 Minutes_xG", Stat.XG),
            entry("Per 90 Minutes_npxG", Stat.NPXG), entry("Per 90 Minutes_xAG", Stat.XA),
            entry("Per 90 Minutes_CrdY", Stat.YELLOW_CARDS), entry("Per 90 Minutes_CrdR", Stat.RED_CARDS),
            entry("Per 90 Minutes_PrgC", Stat.PROG_CARRIES), entry("Per 90 Minutes_PrgP", Stat.PROG_PASSES),
            entry("Per 90 Minutes_PrgR", Stat.PROG_RECEPTIONS), entry("Standard_Sh/90", Stat.SHOTS),
            entry("Standard_SoT/90", Stat.SHOTS_ON_TARGET), entry("Per 90 Minutes_Total_Cmp", Stat.PASSES_COMPLETED),
            entry("Per 90 Minutes_Total_Att", Stat.PASSES_ATTEMPTED), entry("Per 90 Minutes_KP", Stat.KEY_PASSES),
            entry("Per 90 Minutes_1/3", Stat.PASSES_FINAL_THIRD), entry("Per 90 Minutes_PPA", Stat.PASSES_INTO_BOX),
            entry("Per 90 Minutes_CrsPA", Stat.CROSSES_INTO_BOX), entry("SCA_SCA90", Stat.SCA),
            entry("GCA_GCA90", Stat.GCA), entry("Per 90 Minutes_Tackles_Tkl", Stat.TACKLES),
            entry("Per 90 Minutes_Tackles_TklW", Stat.TACKLES_WON), entry("Per 90 Minutes_Blocks_Blocks", Stat.BLOCKS),
            entry("Per 90 Minutes_Int", Stat.INTERCEPTIONS), entry("Per 90 Minutes_Clr", Stat.CLEARANCES),
            entry("Performance_GA90", Stat.GOALS_AGAINST),
            entry("Per 90 Minutes_Performance_SoTA", Stat.SHOTS_ON_TARGET_AGAINST),
            entry("Per 90 Minutes_Performance_Saves", Stat.SAVES),
            entry("Per 90 Minutes_Performance_CS", Stat.CLEAN_SHEETS));

    /**
     * Rows of the FBref archive, skipping league-seasons in {@code skip} ("LEAGUE|season", covered by a fuller
     * export) and leagues outside {@link #LEAGUES} (MLS).
     */
    public static List<SourceRow> readArchive(Path csv, Set<String> skip) {
        List<SourceRow> rows = new ArrayList<>();
        Csv.read(csv, r -> {
            League league = league(r.get("league"));
            int season = season(r.get("season"));
            String name = r.get("player").trim();
            double minutes = r.num("Playing Time_Min");
            if (league == null || season < 0 || name.isEmpty() || !(minutes > 0)
                    || skip.contains(league.name() + "|" + season)) {
                return;
            }
            double[] s = PlayerSeason.emptyStats();
            s[Stat.MINUTES.ordinal()] = minutes;
            s[Stat.GAMES.ordinal()] = r.num("Playing Time_MP");
            s[Stat.STARTS.ordinal()] = r.num("Playing Time_Starts");
            for (Map.Entry<String, Stat> e : ARCHIVE_PER90.entrySet()) {
                double per90 = r.hasColumn(e.getKey()) ? r.num(e.getKey()) : Double.NaN;
                if (!Double.isNaN(per90)) {
                    s[e.getValue().ordinal()] = Math.round(per90 * minutes / 90 * 10) / 10.0;
                }
            }
            for (Stat c : COUNTS) {
                if (!Double.isNaN(s[c.ordinal()])) {
                    s[c.ordinal()] = Math.round(s[c.ordinal()]);
                }
            }
            String pos = r.get("pos");
            if (!pos.contains("GK")) {
                for (Stat g : KEEPING) {
                    s[g.ordinal()] = Double.NaN;
                }
            }
            int born = (int) zero(r.num("born_fbref"));
            rows.add(new SourceRow(Source.FBREF, name + "|" + born, name, born, season, league, r.get("team"),
                    r.get("team"), pos, s));
        });
        return rows;
    }

    // ------------------------------------------------------------------------------------------------ 2025/26

    private static final Map<String, Stat> SEASON_2526 = Map.ofEntries(
            entry("Playing Time_MP", Stat.GAMES), entry("Playing Time_Starts", Stat.STARTS),
            entry("Playing Time_Min", Stat.MINUTES), entry("Performance_Gls", Stat.GOALS),
            entry("Performance_G-PK", Stat.NP_GOALS), entry("Performance_Ast", Stat.ASSISTS),
            entry("Performance_CrdY", Stat.YELLOW_CARDS), entry("Performance_CrdR", Stat.RED_CARDS),
            entry("Standard_Sh", Stat.SHOTS), entry("Standard_SoT", Stat.SHOTS_ON_TARGET),
            entry("Performance_Int", Stat.INTERCEPTIONS), entry("Performance_TklW", Stat.TACKLES_WON),
            entry("Performance_Crs", Stat.CROSSES), entry("Performance_Fld", Stat.FOULS_DRAWN),
            // Understat
            entry("xg_under", Stat.XG), entry("np_xg_under", Stat.NPXG), entry("xa_under", Stat.XA),
            entry("xg_chain_under", Stat.XG_CHAIN), entry("xg_buildup_under", Stat.XG_BUILDUP),
            entry("key_passes_under", Stat.KEY_PASSES),
            // Sofascore
            entry("tackles", Stat.TACKLES), entry("outfielderBlocks", Stat.BLOCKS),
            entry("clearances", Stat.CLEARANCES), entry("ballRecovery", Stat.RECOVERIES),
            entry("aerialDuelsWon", Stat.AERIALS_WON), entry("successfulDribbles", Stat.TAKE_ONS),
            entry("touches", Stat.TOUCHES), entry("dispossessed", Stat.DISPOSSESSED),
            entry("accuratePasses", Stat.PASSES_COMPLETED), entry("totalPasses", Stat.PASSES_ATTEMPTED),
            entry("saves", Stat.SAVES), entry("goalsConceded", Stat.GOALS_AGAINST),
            entry("cleanSheet", Stat.CLEAN_SHEETS));

    public static List<SourceRow> read2526(Path csv) {
        List<SourceRow> rows = new ArrayList<>();
        Csv.read(csv, r -> {
            League league = league(r.get("league"));
            String name = r.get("player").trim();
            double minutes = r.num("Playing Time_Min");
            if (league == null || season(r.get("season")) != 2025 || name.isEmpty() || !(minutes > 0)) {
                return;
            }
            double[] s = PlayerSeason.emptyStats();
            for (Map.Entry<String, Stat> e : SEASON_2526.entrySet()) {
                if (r.hasColumn(e.getKey())) {
                    s[e.getValue().ordinal()] = r.num(e.getKey());
                }
            }
            String pos = r.get("pos_");
            if (!pos.contains("GK")) {
                for (Stat g : KEEPING) {
                    s[g.ordinal()] = Double.NaN;
                }
            }
            int born = (int) zero(r.num("born_"));
            rows.add(new SourceRow(Source.SOFASCORE, name + "|" + born, name, born, 2025, league, r.get("team"),
                    r.get("team"), pos, s));
        });
        return rows;
    }

    private static double zero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }
}

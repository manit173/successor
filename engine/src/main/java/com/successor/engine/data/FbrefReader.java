package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

/**
 * Reads an FBref "Big 5" season export. The 2024/25 file has the full Opta profile; the 2025/26 file only the basic
 * tables (FBref lost the Opta advanced data in January 2026), so absent columns simply stay NaN.
 */
public final class FbrefReader {
    private FbrefReader() {
    }

    /** FBref column → our stat. xAG (expected assisted goals) is the counterpart of Understat's xA. */
    private static final Map<String, Stat> COLUMNS = Map.ofEntries(
            entry("MP", Stat.GAMES), entry("Starts", Stat.STARTS), entry("Min", Stat.MINUTES),
            entry("Gls", Stat.GOALS), entry("G-PK", Stat.NP_GOALS), entry("Ast", Stat.ASSISTS),
            entry("Sh", Stat.SHOTS), entry("SoT", Stat.SHOTS_ON_TARGET), entry("xG", Stat.XG),
            entry("npxG", Stat.NPXG), entry("xAG", Stat.XA), entry("KP", Stat.KEY_PASSES), entry("SCA", Stat.SCA),
            entry("GCA", Stat.GCA), entry("PrgP", Stat.PROG_PASSES), entry("PrgC", Stat.PROG_CARRIES),
            entry("PrgR", Stat.PROG_RECEPTIONS), entry("1/3", Stat.PASSES_FINAL_THIRD),
            entry("PPA", Stat.PASSES_INTO_BOX), entry("CrsPA", Stat.CROSSES_INTO_BOX),
            entry("CPA", Stat.CARRIES_INTO_BOX), entry("Cmp", Stat.PASSES_COMPLETED),
            entry("Att", Stat.PASSES_ATTEMPTED), entry("Touches", Stat.TOUCHES), entry("Att Pen", Stat.TOUCHES_ATT_BOX),
            entry("Succ", Stat.TAKE_ONS), entry("Crs", Stat.CROSSES), entry("Fld", Stat.FOULS_DRAWN),
            entry("Tkl", Stat.TACKLES), entry("TklW", Stat.TACKLES_WON), entry("Int", Stat.INTERCEPTIONS),
            entry("Blocks", Stat.BLOCKS), entry("Clr", Stat.CLEARANCES), entry("Recov", Stat.RECOVERIES),
            entry("Won", Stat.AERIALS_WON), entry("Dis", Stat.DISPOSSESSED), entry("Mis", Stat.MISCONTROLS),
            entry("CrdY", Stat.YELLOW_CARDS), entry("CrdR", Stat.RED_CARDS), entry("GA", Stat.GOALS_AGAINST),
            entry("SoTA", Stat.SHOTS_ON_TARGET_AGAINST), entry("Saves", Stat.SAVES), entry("CS", Stat.CLEAN_SHEETS),
            entry("PSxG", Stat.PSXG));

    public static List<SourceRow> read(Path csv, int season, Source source) {
        List<SourceRow> rows = new ArrayList<>();
        Csv.read(csv, r -> {
            League league = League.fromFbref(r.get("Comp"));
            String name = r.get("Player").trim();
            if (league == null || name.isEmpty()) {
                return;
            }
            double[] s = PlayerSeason.emptyStats();
            for (Map.Entry<String, Stat> e : COLUMNS.entrySet()) {
                if (r.hasColumn(e.getKey())) {
                    s[e.getValue().ordinal()] = r.num(e.getKey());
                }
            }
            boolean keeper = r.get("Pos").contains("GK");
            if (!keeper) { // goalkeeping columns are blank for outfielders: not measured, not zero
                for (Stat g : new Stat[]{Stat.GOALS_AGAINST, Stat.SHOTS_ON_TARGET_AGAINST, Stat.SAVES,
                        Stat.CLEAN_SHEETS, Stat.PSXG}) {
                    s[g.ordinal()] = Double.NaN;
                }
            }
            int born = (int) zero(r.num("Born"));
            rows.add(new SourceRow(source, name + "|" + born, name, born, season, league, r.get("Squad"),
                    r.get("Squad"), r.get("Pos"), s));
        });
        return rows;
    }

    private static double zero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }
}

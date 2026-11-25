package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Understat mirror (player_stats.csv has no league column, so each club-season's league comes from
 * game_stats.csv). Seasons after {@code lastSeason} are skipped: the mirror's last season stops after a few weeks.
 */
public final class UnderstatReader {
    private UnderstatReader() {
    }

    public static List<SourceRow> read(Path dir, int firstSeason, int lastSeason) {
        Map<String, League> leagueOf = new HashMap<>();
        Csv.read(dir.resolve("game_stats.csv"), r -> {
            League l = League.fromUnderstat(r.get("league"));
            if (l != null) {
                leagueOf.put(r.get("season") + "|" + r.get("club_name"), l);
            }
        });
        List<SourceRow> rows = new ArrayList<>();
        Csv.read(dir.resolve("player_stats.csv"), r -> {
            int season = (int) r.num("season");
            League league = leagueOf.get(r.get("season") + "|" + r.get("team"));
            if (league == null || season < firstSeason || season > lastSeason) {
                return; // the Russian league (not used), or outside the complete seasons
            }
            double[] s = PlayerSeason.emptyStats();
            put(s, Stat.GAMES, r.num("games"));
            put(s, Stat.MINUTES, r.num("time"));
            put(s, Stat.GOALS, r.num("goals"));
            put(s, Stat.NP_GOALS, r.num("npg"));
            put(s, Stat.ASSISTS, r.num("assists"));
            put(s, Stat.SHOTS, r.num("shots"));
            put(s, Stat.XG, r.num("xG"));
            put(s, Stat.NPXG, r.num("npxG"));
            put(s, Stat.XA, r.num("xA"));
            put(s, Stat.KEY_PASSES, r.num("key_passes"));
            put(s, Stat.XG_CHAIN, r.num("xGChain"));
            put(s, Stat.XG_BUILDUP, r.num("xGBuildup"));
            put(s, Stat.YELLOW_CARDS, r.num("yellow"));
            put(s, Stat.RED_CARDS, r.num("red"));
            rows.add(new SourceRow(Source.UNDERSTAT, r.get("player_id"), r.get("name"), 0, season, league,
                    r.get("team"), r.get("team"), r.get("position"), s));
        });
        return rows;
    }

    static void put(double[] s, Stat stat, double v) {
        s[stat.ordinal()] = v;
    }
}

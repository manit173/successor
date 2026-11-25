package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.Stat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewSourcesTest {

    private static TransfermarktReader.Event ev(int minute, String type, String player, String in, String assist,
                                                String description) {
        return new TransfermarktReader.Event(minute, type, player, in, assist, description.toLowerCase());
    }

    @Test
    void aGameIsRebuiltFromLineupAndEvents() {
        List<String[]> squad = List.of(
                new String[]{"1", "Starter", "10", "starting_lineup"},
                new String[]{"2", "Subbed off", "10", "starting_lineup"},
                new String[]{"3", "Sent off", "10", "starting_lineup"},
                new String[]{"4", "Came on", "10", "substitutes"},
                new String[]{"5", "Unused", "10", "substitutes"});
        List<TransfermarktReader.Event> events = List.of(
                ev(60, "Substitutions", "2", "4", "", ", Tactical"),
                ev(70, "Goals", "4", "", "1", "1. Goal, Right-footed shot"),
                ev(80, "Goals", "1", "", "", "Own-goal"), // an own goal is not his goal
                ev(30, "Cards", "3", "", "", "1. Yellow card , Foul"),
                ev(75, "Cards", "3", "", "", "Second yellow , Foul"),
                ev(95, "Cards", "1", "", "", "Red card , Violent conduct")); // stoppage time counts as 90'
        Map<String, TransfermarktReader.Line> lines = TransfermarktReader.rebuildGame(squad, events).stream()
                .collect(Collectors.toMap(TransfermarktReader.Line::player, Function.identity()));
        assertEquals(4, lines.size(), "the unused substitute did not play");
        assertEquals(90, lines.get("1").minutes());
        assertEquals(1, lines.get("1").assists());
        assertEquals(0, lines.get("1").goals());
        assertEquals(1, lines.get("1").red());
        assertEquals(60, lines.get("2").minutes());
        assertEquals(30, lines.get("4").minutes());
        assertEquals(1, lines.get("4").goals());
        assertEquals(75, lines.get("3").minutes());
        assertEquals(2, lines.get("3").yellow(), "a second yellow counts as a yellow card, as Transfermarkt does");
        assertEquals(0, lines.get("3").red());
    }

    @Test
    void soccerdataLeaguesAndSeasonsAreRead() {
        assertEquals(League.EREDIVISIE, SoccerdataSets.league("NED-Eredivisie"));
        assertNull(SoccerdataSets.league("USA-MLS"));
        assertEquals(2017, SoccerdataSets.season("1718"));
        assertEquals(-1, SoccerdataSets.season("2017"));
        assertNull(League.fromUnderstat("RFPL"), "the Russian league is not used");
        assertNull(League.fromLabel("Russian Premier League"));
    }

    @Test
    void archivePer90BecomesTotalsAndSkipsCoveredSeasons(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.csv");
        Files.writeString(f, String.join("\n",
                "league,season,team,player,pos,born_fbref,Playing Time_MP,Playing Time_Starts,Playing Time_Min,"
                        + "Per 90 Minutes_Gls,Per 90 Minutes_xG,Per 90 Minutes_Tackles_Tkl,Performance_GA90",
                "NED-Eredivisie,2122,Ajax,Antony,FW,2000,25,23,1800,0.42,0.33,1.1,",
                "ENG-Premier League,2122,Arsenal,Saka,FW,2001,38,36,3000,0.36,0.30,1.0,",
                "USA-MLS,2122,LAFC,Vela,FW,1989,20,20,1700,0.5,0.4,0.2,",
                "NED-Eredivisie,2122,Ajax,Remko Pasveer,GK,1983,30,30,2700,0,0,0,0.80"));
        List<SourceRow> rows = SoccerdataSets.readArchive(f, Set.of("PREMIER_LEAGUE|2021"));
        assertEquals(2, rows.size(), "the covered Premier League season and MLS are skipped");
        SourceRow antony = rows.get(0);
        assertEquals(2021, antony.season());
        assertEquals(8, antony.stats()[Stat.GOALS.ordinal()], 0.0, "0.42 × 20 = 8.4, a count, rounds to 8");
        assertEquals(6.6, antony.stats()[Stat.XG.ordinal()], 1e-9);
        assertTrue(Double.isNaN(antony.stats()[Stat.GOALS_AGAINST.ordinal()]), "keeping stats only for keepers");
        assertEquals(24, rows.get(1).stats()[Stat.GOALS_AGAINST.ordinal()], 1e-9);
    }

    @Test
    void season2526TakesUnderstatAndSofascoreColumns(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("b.csv");
        Files.writeString(f, String.join("\n",
                "league,season,team,player,pos_,born_,Playing Time_Min,Performance_Gls,xg_under,key_passes_under,"
                        + "keyPasses,tackles,saves",
                "ENG-Premier League,2526,Arsenal,Ben White,DF,1997,702,0,0.28,8,9,15,0",
                "ENG-Premier League,2425,Arsenal,Old Season,DF,1997,702,0,0.28,8,9,15,0"));
        List<SourceRow> rows = SoccerdataSets.read2526(f);
        assertEquals(1, rows.size(), "only 2025/26");
        double[] s = rows.get(0).stats();
        assertEquals(0.28, s[Stat.XG.ordinal()], 1e-9);
        assertEquals(8, s[Stat.KEY_PASSES.ordinal()], 0.0, "Understat key passes, as in earlier seasons");
        assertEquals(15, s[Stat.TACKLES.ordinal()], 0.0);
        assertTrue(Double.isNaN(s[Stat.SAVES.ordinal()]));
        assertEquals(com.successor.engine.model.Source.SOFASCORE, rows.get(0).source());
    }
}

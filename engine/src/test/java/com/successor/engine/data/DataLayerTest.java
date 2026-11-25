package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Names, CSV, linking, de-duplication and dataset round trips. */
class DataLayerTest {

    @Test
    void namesNormaliseAccentsAndSpecialLetters() {
        assertEquals("martin odegaard", Names.normalize("Martin Ødegaard"));
        assertEquals("dusan vlahovic", Names.normalize("Dušan Vlahović"));
        assertEquals("thomas muller", Names.normalize("Thomas Müller"));
        assertEquals("fussballclub heidenheim 1846", Names.normalize("Fußballclub Heidenheim 1846"));
        assertEquals("", Names.normalize("  "));
    }

    @Test
    void personSimilarityTrustsSurnamesNotScatteredLetters() {
        assertTrue(Names.personSimilarity("Mathis Cherki", "Rayan Cherki") >= 0.8, "same surname");
        assertEquals(1.0, Names.personSimilarity("Kevin De Bruyne", "Kevin De Bruyne"));
        assertTrue(Names.personSimilarity("Paik Seung-Ho", "Xavi Quintillà") < 0.5, "different people");
        assertTrue(Names.personSimilarity("Isaac Palazón Camacho", "Isi Palazón") >= 0.5);
    }

    @Test
    void clubSimilarityKeepsRealSociedadAndVillarrealApart() {
        assertTrue(Names.clubSimilarity("Villarreal", "Villarreal CF") > Names.clubSimilarity("Villarreal",
                "Real Sociedad"));
        assertTrue(Names.clubSimilarity("Real Sociedad", "Real Sociedad") > Names.clubSimilarity("Real Sociedad",
                "Villarreal CF"));
        assertTrue(Names.clubSimilarity("Borussia M.Gladbach", "Borussia Mönchengladbach") >= 0.85);
        assertTrue(Names.clubSimilarity("FC Cologne", "1. FC Köln") >= 0.85, "alias");
        assertTrue(Names.clubSimilarity("Nott'ham Forest", "Nottingham Forest") >= 0.85);
    }

    @Test
    void linkerResolvesTheMilanTieByOneToOneMatching() {
        // "AC Milan" → {milan} matches both Milan clubs equally; only a one-to-one assignment gets both right
        List<SourceRow> tm = List.of(row(Source.TRANSFERMARKT, "1", "Rafael Leão", "tmMilan", "AC Milan", 2500),
                row(Source.TRANSFERMARKT, "2", "Lautaro Martínez", "tmInter", "Inter Milan", 2700));
        List<SourceRow> us = List.of(row(Source.UNDERSTAT, "u1", "Rafael Leão", "AC Milan", "AC Milan", 2500),
                row(Source.UNDERSTAT, "u2", "Lautaro Martínez", "Inter", "Inter", 2700));
        Linker.Result r = Linker.link(tm, us);
        assertEquals("1", r.tmIdOf().get(us.get(0)));
        assertEquals("2", r.tmIdOf().get(us.get(1)));
        assertEquals("tmMilan", r.clubKeyFor(us.get(0)));
        assertEquals("tmInter", r.clubKeyFor(us.get(1)));
    }

    @Test
    void linkerLinksRenamedPlayerAndLeavesStrangersUnlinked() {
        List<SourceRow> tm = List.of(row(Source.TRANSFERMARKT, "607223", "Rayan Cherki", "lyon", "Olympique Lyon", 1871),
                row(Source.TRANSFERMARKT, "9", "Alexandre Lacazette", "lyon", "Olympique Lyon", 2800));
        SourceRow cherki = row(Source.UNDERSTAT, "u1", "Mathis Cherki", "Lyon", "Lyon", 1871);
        SourceRow stranger = row(Source.UNDERSTAT, "u2", "Someone Else", "Lyon", "Lyon", 2800);
        Linker.Result r = Linker.link(tm, List.of(cherki, stranger));
        assertEquals("607223", r.tmIdOf().get(cherki));
        assertNull(r.tmIdOf().get(stranger), "equal minutes alone must not link different names");
    }

    @Test
    void deduperDropsRepeatsAndNeverDropsNewKeys() {
        Deduper d = new Deduper(1000, 0.01);
        int kept = 0;
        for (int i = 0; i < 1000; i++) {
            if (d.firstTime("k" + (i % 700))) {
                kept++;
            }
        }
        assertEquals(700, kept);
        assertEquals(300, d.duplicates());
        assertEquals(700, d.definitelyNew() + d.falsePositives());
    }

    @Test
    void csvHandlesQuotesCommasAndNewlines(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "a,b,c\n1,\"x, y\",\"say \"\"hi\"\"\"\n2,\"two\nlines\",+3.5\n\n");
        List<String> seen = new ArrayList<>();
        long n = Csv.read(f, r -> seen.add(r.get("a") + "|" + r.get("b") + "|" + r.get("c") + "|" + r.num("c")));
        assertEquals(2, n);
        assertEquals("1|x, y|say \"hi\"|NaN", seen.get(0));
        assertEquals("2|two\nlines|+3.5|3.5", seen.get(1));
    }

    @Test
    void edgeCaseMalformedCsvFailsLoudly(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("bad.csv");
        Files.writeString(f, "a,b\n1,\"never closed\n");
        assertThrows(IllegalArgumentException.class, () -> Csv.read(f, r -> {
        }));
    }

    @Test
    void datasetRoundTripsThroughCsvKeepingMissingStatsMissing(@TempDir Path dir) {
        Dataset d = SyntheticData.generate(200, 2015, 6, 42);
        DatasetIO.write(d, dir);
        Dataset back = DatasetIO.read(dir);
        assertEquals(d.players().size(), back.players().size());
        assertEquals(d.seasons().size(), back.seasons().size());
        PlayerSeason a = d.seasons().get(17);
        PlayerSeason b = back.seasons().get(17);
        assertEquals(a.playerId(), b.playerId());
        assertEquals(a.get(Stat.MINUTES), b.get(Stat.MINUTES), 1e-9);
        assertEquals(a.get(Stat.XG), b.get(Stat.XG), 1e-3);
        assertEquals(a.has(Stat.SAVES), b.has(Stat.SAVES), "missing stays missing, not 0");
    }

    @Test
    void edgeCaseMissingDatasetIsAClearError(@TempDir Path dir) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DatasetIO.read(dir));
        assertTrue(e.getMessage().contains("players.csv"));
    }

    @Test
    void syntheticDataIsDeterministicAndPlausible() {
        Dataset a = SyntheticData.generate(500, 2014, 10, 7);
        Dataset b = SyntheticData.generate(500, 2014, 10, 7);
        assertEquals(a.seasons().size(), b.seasons().size());
        assertEquals(a.seasons().get(3).get(Stat.XG), b.seasons().get(3).get(Stat.XG));
        for (PlayerSeason s : a.seasons()) {
            assertTrue(s.minutes() >= 90 && s.minutes() <= 3420, "minutes " + s.minutes());
            assertFalse(Double.isNaN(s.get(Stat.TACKLES)), "synthetic data has every stat");
        }
    }

    private static SourceRow row(Source src, String key, String name, String clubKey, String club, double minutes) {
        double[] s = PlayerSeason.emptyStats();
        s[Stat.MINUTES.ordinal()] = minutes;
        return new SourceRow(src, key, name, 0, 2022, League.SERIE_A, clubKey, club, "", s);
    }
}

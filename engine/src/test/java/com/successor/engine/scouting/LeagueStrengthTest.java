package com.successor.engine.scouting;

import com.successor.engine.data.Dataset;
import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueStrengthTest {

    /** Builds movers: season 2018 in {@code from} at {@code before} goals per 90, 2019 in the Premier League. */
    private static void movers(List<Player> ps, List<PlayerSeason> ss, String tag, League from, int count,
                               java.util.function.IntToDoubleFunction before,
                               java.util.function.IntToDoubleFunction after) {
        for (int i = 0; i < count; i++) {
            String id = tag + i;
            ps.add(new Player(id, id, List.of(), null, 1995, "", Position.ST, "", "", 0, 0));
            ss.add(season(id, 2018, from, "A", before.applyAsDouble(i)));
            ss.add(season(id, 2019, League.PREMIER_LEAGUE, "B", after.applyAsDouble(i)));
        }
    }

    private static PlayerSeason season(String id, int year, League l, String club, double goalsPer90) {
        double[] s = PlayerSeason.emptyStats();
        s[Stat.MINUTES.ordinal()] = 1800;
        s[Stat.NP_GOALS.ordinal()] = goalsPer90 * 20; // 1800 minutes = 20 × 90
        return new PlayerSeason(id, year, l, club, Position.ST, EnumSet.of(Source.SYNTHETIC), s);
    }

    private static Dataset world() {
        List<Player> ps = new ArrayList<>();
        List<PlayerSeason> ss = new ArrayList<>();
        movers(ps, ss, "big", League.LA_LIGA, 200, i -> 0.2 + 0.003 * i, i -> 0.2 + 0.003 * i); // baseline ×1.0
        movers(ps, ss, "ned", League.EREDIVISIE, 60, i -> 0.3 + 0.01 * i, i -> 0.6 * (0.3 + 0.01 * i)); // ×0.6
        movers(ps, ss, "bel", League.BELGIAN_PRO_LEAGUE, 10, i -> 0.5, i -> i % 2 == 0 ? 0.25 : 0.65); // noisy ×0.9
        movers(ps, ss, "den", League.DANISH_SUPERLIGA, 3, i -> 0.5, i -> 0.5); // too few to count
        return new Dataset(ps, ss);
    }

    @Test
    void aWellMeasuredLeagueKeepsItsOwnFactor() {
        LeagueStrength s = LeagueStrength.estimate(world(), 900, 2025);
        assertEquals(0.6, s.factor(League.EREDIVISIE, Stat.NP_GOALS), 0.01);
        assertEquals(1.0, s.factor(League.LA_LIGA, Stat.NP_GOALS), 0.0, "Big 5 are never adjusted");
    }

    @Test
    void aSmallSampleIsShrunkTowardTheTypicalLeague() {
        LeagueStrength s = LeagueStrength.estimate(world(), 900, 2025);
        double raw = s.estimates().stream().filter(e -> e.league() == League.BELGIAN_PRO_LEAGUE
                && e.stat() == Stat.NP_GOALS).findFirst().orElseThrow().raw();
        double f = s.factor(League.BELGIAN_PRO_LEAGUE, Stat.NP_GOALS);
        assertEquals(0.9, raw, 1e-9);
        assertTrue(f < raw && f > 0.6, "between its own 0.9 and the typical 0.6, got " + f);
        // three movers are not evidence at all: the typical league's factor
        assertEquals(0.6, s.factor(League.DANISH_SUPERLIGA, Stat.NP_GOALS), 0.01);
    }

    @Test
    void factorsOnlyUseTransfersUpToTheCutoff() {
        // every Big-5 season here is 2019, so a cutoff of 2018 leaves no evidence: no adjustment at all
        LeagueStrength s = LeagueStrength.estimate(world(), 900, 2018);
        assertEquals(1.0, s.factor(League.EREDIVISIE, Stat.NP_GOALS), 0.0);
    }

    @Test
    void cohortsApplyTheFactorOutsideTheBigFiveOnly() {
        Dataset d = world();
        Cohorts c = new Cohorts(d, 900);
        PlayerSeason ned = season("x", 2018, League.EREDIVISIE, "A", 0.5);
        PlayerSeason esp = season("y", 2018, League.LA_LIGA, "A", 0.5);
        assertEquals(0.5, c.value(ned, Stat.NP_GOALS), 1e-9, "no factors set: raw");
        c.setStrength(LeagueStrength.estimate(d, 900, 2025));
        assertEquals(0.3, c.value(ned, Stat.NP_GOALS), 0.01);
        assertEquals(0.5, c.value(esp, Stat.NP_GOALS), 1e-9);
    }

    @Test
    void propertiesRoundTripAndBadEntriesAreRejected() {
        LeagueStrength s = LeagueStrength.estimate(world(), 900, 2025);
        Properties p = new Properties();
        try {
            p.load(new java.io.StringReader(s.toProperties()));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        LeagueStrength back = LeagueStrength.fromProperties(p);
        assertEquals(s.factor(League.EREDIVISIE, Stat.NP_GOALS), back.factor(League.EREDIVISIE, Stat.NP_GOALS), 1e-4);
        assertEquals(2025, back.lastTargetSeason());
        Properties bad = new Properties();
        bad.setProperty("PREMIER_LEAGUE.np_goals", "0.5"); // the yardstick cannot be adjusted
        assertThrows(IllegalArgumentException.class, () -> LeagueStrength.fromProperties(bad));
        Properties negative = new Properties();
        negative.setProperty("EREDIVISIE.np_goals", "-1");
        assertThrows(IllegalArgumentException.class, () -> LeagueStrength.fromProperties(negative));
    }

    @Test
    void edgeCaseNoDataMeansNoAdjustment() {
        LeagueStrength s = LeagueStrength.estimate(new Dataset(List.of(), List.of()), 900, 2025);
        assertEquals(1.0, s.factor(League.EREDIVISIE, Stat.NP_GOALS), 0.0);
        assertEquals(1.0, LeagueStrength.none().factor(League.SUPER_LIG, Stat.TACKLES), 0.0);
        assertEquals(null, LeagueStrength.none().headline(League.EREDIVISIE));
    }
}

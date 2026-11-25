package com.successor.engine.scouting;

import com.successor.engine.data.Dataset;
import com.successor.engine.data.SyntheticData;
import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cohorts (quickselect quantiles), role fits, peaks and the successor search. */
class ScoutTest {

    private static final Dataset BASE = SyntheticData.generate(3000, 2016, 10, 21);
    private static final int LAST = 2025;

    /** BASE plus a target with two strong recent AM seasons and an exact statistical clone of him. */
    private static Dataset withTargetAndClone() {
        List<Player> players = new ArrayList<>(BASE.players());
        List<PlayerSeason> seasons = new ArrayList<>(BASE.seasons());
        for (String id : List.of("t:target", "t:clone")) {
            players.add(new Player(id, id.equals("t:target") ? "Target Player" : "Clone Player", List.of(),
                    LocalDate.of(2003, 1, 1), 0, "", Position.AM, "", "", 0, 0));
            for (int season : new int[]{LAST - 1, LAST}) {
                double[] s = PlayerSeason.emptyStats();
                for (Stat st : Stat.values()) {
                    s[st.ordinal()] = st.per90() ? 2700 / 90.0 * 0.9 : Double.NaN;
                }
                s[Stat.MINUTES.ordinal()] = 2700;
                s[Stat.XA.ordinal()] = 15;
                s[Stat.KEY_PASSES.ordinal()] = 95;
                s[Stat.SCA.ordinal()] = 160;
                for (Stat g : new Stat[]{Stat.GOALS_AGAINST, Stat.SAVES, Stat.PSXG, Stat.SHOTS_ON_TARGET_AGAINST}) {
                    s[g.ordinal()] = Double.NaN;
                }
                seasons.add(new PlayerSeason(id, season, League.LA_LIGA, "Club", Position.AM,
                        EnumSet.of(Source.SYNTHETIC), s));
            }
        }
        return new Dataset(players, seasons);
    }

    @Test
    void cohortQuantilesFromQuickselectEqualSortedQuantiles() {
        Cohorts c = new Cohorts(BASE, 900);
        Cohorts.StatScale xa = c.cohort(Position.AM, 2020).stat(Stat.XA);
        double[] sorted = xa.values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        double h = 0.5 * (n - 1);
        double expectedMedian = sorted[(int) h] + (h - (int) h) * (sorted[Math.min(n - 1, (int) h + 1)] - sorted[(int) h]);
        assertEquals(expectedMedian, xa.median, 1e-12);
        double h1 = 0.25 * (n - 1);
        assertEquals(sorted[(int) h1] + (h1 - (int) h1) * (sorted[(int) h1 + 1] - sorted[(int) h1]), xa.q1, 1e-12);
        assertTrue(xa.scale > 0);
        assertTrue(c.work() > 0, "quickselect did the work");
    }

    @Test
    void percentilesAreMidRanksAndZScoresCentreOnTheMedian() {
        Cohorts.StatScale sc = new Cohorts.StatScale(new double[]{1, 2, 2, 3}, 2, 1.75, 2.25, 0.5 / 1.349);
        assertEquals(50, sc.percentile(2), 1e-12); // 1 below + half of 2 equal = 2 of 4
        assertEquals(100, sc.percentile(9), 1e-12);
        assertEquals(0, sc.percentile(0), 1e-12);
        assertEquals(0, sc.z(2), 1e-12);
        assertEquals(1.349 * 2, sc.z(3), 1e-9);
    }

    @Test
    void roleFitNeedsAThirdOfTheRoleMeasured() {
        Scout s = new Scout(BASE, 900);
        Cohorts.Cohort c = s.cohorts().cohort(Position.CM, 2020);
        double[] onlyMinutes = PlayerSeason.emptyStats();
        onlyMinutes[Stat.MINUTES.ordinal()] = 2000;
        PlayerSeason bare = new PlayerSeason("x", 2020, League.SERIE_A, "C", Position.CM, EnumSet.of(Source.SYNTHETIC),
                onlyMinutes);
        Scout.RoleFit f = s.fit(bare, Roles.byId("box-to-box"), c);
        assertTrue(Double.isNaN(f.fit()), "nothing measured: no fit, not a fit of 0");
        assertEquals(0, f.coverage(), 1e-12);
        // a season at the top of every box-to-box stat fits ~100
        double[] top = onlyMinutes.clone();
        for (Stat st : Roles.byId("box-to-box").weights().keySet()) {
            top[st.ordinal()] = 1000;
        }
        PlayerSeason elite = new PlayerSeason("y", 2020, League.SERIE_A, "C", Position.CM, EnumSet.of(Source.SYNTHETIC),
                top);
        assertTrue(s.fit(elite, Roles.byId("box-to-box"), c).fit() > 99);
    }

    @Test
    void anExactCloneIsTheTopSuccessorAtFullSimilarity() {
        Scout s = new Scout(withTargetAndClone(), 900);
        // comparing recent seasons: an identical player is at distance 0
        Scout.Result r = s.successor(new Scout.Request("t:target", null, null, "AM", null, null, null, null, null, 5,
                false));
        assertEquals("t:clone", r.candidates().getFirst().id());
        assertEquals(0, r.candidates().getFirst().distance(), 1e-9);
        assertEquals(100, r.candidates().getFirst().similarity(), 1e-9);
        // comparing projections (the default) adds the aging curve, so not exactly 0, but still the closest
        Scout.Result projected = s.successor(new Scout.Request("t:target", null, null, "AM", null, null, null, null,
                null, 5));
        assertEquals("t:clone", projected.candidates().getFirst().id());
        assertTrue(projected.candidates().getFirst().distance() < 0.3);
        assertTrue(r.candidates().stream().noneMatch(c -> c.id().equals("t:target")), "never his own successor");
        for (int i = 1; i < r.candidates().size(); i++) {
            assertTrue(r.candidates().get(i - 1).distance() <= r.candidates().get(i).distance(), "ascending distance");
        }
    }

    @Test
    void topKIsAPrefixOfTheFullRankingAndFiltersHold() {
        Scout s = new Scout(withTargetAndClone(), 900);
        Scout.Result all = s.successor(new Scout.Request("t:target", null, null, "AM", null, null, null, null, null, 100));
        Scout.Result top3 = s.successor(new Scout.Request("t:target", null, null, "AM", null, null, null, null, null, 3));
        assertEquals(all.candidates().subList(0, 3).stream().map(Scout.Candidate::id).toList(),
                top3.candidates().stream().map(Scout.Candidate::id).toList());
        Scout.Result young = s.successor(new Scout.Request("t:target", null, null, "AM", null, 21, List.of("Serie A"),
                List.of("AM", "W"), null, 50));
        for (Scout.Candidate c : young.candidates()) {
            assertTrue(c.ageNextSeason() <= 21, c.name() + " is " + c.ageNextSeason());
            assertEquals("Serie A", c.league());
            assertTrue(c.position().equals("AM") || c.position().equals("W"));
        }
    }

    @Test
    void differentPositionsAreFlaggedAndCanBeExcluded() {
        Scout s = new Scout(withTargetAndClone(), 900);
        Scout.Result mixed = s.successor(new Scout.Request("t:target", null, null, "AM", null, null, null, null, null,
                40, null, false));
        assertTrue(mixed.candidates().stream().anyMatch(c -> !c.samePosition()), "neighbours included by default");
        for (Scout.Candidate c : mixed.candidates()) {
            assertEquals(c.position().equals("AM"), c.samePosition(), c.name());
            assertEquals(c.samePosition(), c.positionNote() == null, "a note exactly when the position differs");
        }
        Scout.Result same = s.successor(new Scout.Request("t:target", null, null, "AM", null, null, null, null, null,
                40, null, true));
        assertFalse(same.candidates().isEmpty());
        assertTrue(same.candidates().stream().allMatch(Scout.Candidate::samePosition));
    }

    @Test
    void otherLeaguesNeverChangeTheBigFiveYardstick() {
        // an outlandish Eredivisie season must not move the AM cohort's median or quartiles
        List<Player> players = new ArrayList<>(BASE.players());
        List<PlayerSeason> seasons = new ArrayList<>(BASE.seasons());
        players.add(new Player("x:ere", "Eredivisie Star", List.of(), LocalDate.of(2002, 1, 1), 0, "", Position.AM, "",
                "", 0, 0));
        double[] st = PlayerSeason.emptyStats();
        st[Stat.MINUTES.ordinal()] = 3000;
        st[Stat.XA.ordinal()] = 500;
        seasons.add(new PlayerSeason("x:ere", 2020, League.EREDIVISIE, "Club", Position.AM, EnumSet.of(Source.SYNTHETIC),
                st));
        Cohorts with = new Cohorts(new Dataset(players, seasons), 900);
        Cohorts without = new Cohorts(BASE, 900);
        Cohorts.StatScale a = with.cohort(Position.AM, 2020).stat(Stat.XA);
        Cohorts.StatScale b = without.cohort(Position.AM, 2020).stat(Stat.XA);
        assertEquals(b.median, a.median, 1e-12);
        assertEquals(b.size(), a.size());
        assertTrue(a.percentile(500 * 90 / 3000.0) == 100, "but he can still be placed on the Big-5 scale");
        assertTrue(Scout.leagueNote(League.EREDIVISIE, LeagueStrength.none()).contains("outside the Big 5"));
        assertNull(Scout.leagueNote(League.SERIE_A, LeagueStrength.none()));
    }

    @Test
    void edgeCasesAreClearErrorsOrEmptyResults() {
        Scout s = new Scout(withTargetAndClone(), 900);
        assertThrows(NoSuchElementException.class, () -> s.successor(req("nobody", null)));
        assertThrows(IllegalArgumentException.class, () -> s.successor(req("t:target", 0)));
        assertThrows(IllegalArgumentException.class, () -> s.successor(new Scout.Request("t:target", 2025, 2020, null,
                null, null, null, null, null, 5)));
        assertThrows(IllegalArgumentException.class, () -> s.successor(new Scout.Request("t:target", null, null, "XX",
                null, null, null, null, null, 5)));
        // filters that exclude everyone: an empty list, not an error
        Scout.Result none = s.successor(new Scout.Request("t:target", null, null, "AM", null, 15, null, null, null, 5));
        assertTrue(none.candidates().isEmpty());
        assertFalse(none.target().peak().seasons().isEmpty());
    }

    private static Scout.Request req(String id, Integer k) {
        return new Scout.Request(id, null, null, null, null, null, null, null, null, k);
    }
}

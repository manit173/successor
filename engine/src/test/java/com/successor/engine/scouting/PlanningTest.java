package com.successor.engine.scouting;

import com.successor.engine.data.Dataset;
import com.successor.engine.data.SyntheticData;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Stat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Projection (Marcel, comparables, backtest), squad planning and comparison. */
class PlanningTest {
    private static final Dataset DATA = SyntheticData.generate(3000, 2014, 12, 5);
    private static final Scout SCOUT = new Scout(DATA, 900);

    /** Some player who played at least 900 minutes at {@code pos} in each of the last two seasons. */
    private static String regular(Position pos, int skip) {
        int found = 0;
        for (Player p : DATA.players()) {
            List<PlayerSeason> s = DATA.seasonsOf(p.id());
            long recent = s.stream().filter(x -> x.season() >= 2024 && x.minutes() >= 900 && x.position() == pos)
                    .count();
            if (recent >= 2 && found++ == skip) {
                return p.id();
            }
        }
        throw new IllegalStateException("no regular " + pos);
    }

    @Test
    void backtestBeatsTheNaiveBaselineWithoutTouchingTestSeasons() {
        Projector pr = new Projector(DATA, new Cohorts(DATA, 900));
        Projector.Backtest b = pr.backtest(List.of(2018, 2019), List.of(2021, 2022));
        Projector.MethodScore naive = b.overall().stream().filter(m -> m.method() == Projector.Method.NAIVE)
                .findFirst().orElseThrow();
        Projector.MethodScore marcel = b.overall().stream().filter(m -> m.method() == Projector.Method.MARCEL)
                .findFirst().orElseThrow();
        assertTrue(marcel.mae() < naive.mae(), "Marcel " + marcel.mae() + " vs naive " + naive.mae());
        assertTrue(marcel.coverage80() > 50 && marcel.coverage80() < 97, "coverage " + marcel.coverage80());
        assertTrue(b.trainSeasons().stream().noneMatch(b.testSeasons()::contains));
    }

    @Test
    void projectionsUseNoDataAfterTheCutoff() {
        // same data up to 2020; afterwards one copy loses every season: projections from 2020 must not change
        List<PlayerSeason> upTo2020 = DATA.seasons().stream().filter(s -> s.season() <= 2020).toList();
        Set<String> withSeasons = new HashSet<>();
        upTo2020.forEach(s -> withSeasons.add(s.playerId()));
        Dataset truncated = new Dataset(DATA.players().stream().filter(p -> withSeasons.contains(p.id())).toList(),
                upTo2020);
        Projector full = new Projector(DATA, new Cohorts(DATA, 900));
        Projector cut = new Projector(truncated, new Cohorts(truncated, 900));
        int checked = 0;
        for (Player p : truncated.players()) {
            Projector.Projection a = full.project(p.id(), Position.CM, 2020, Projector.Method.BLEND);
            Projector.Projection b = cut.project(p.id(), Position.CM, 2020, Projector.Method.BLEND);
            if (a == null) {
                assertNull(b);
                continue;
            }
            assertArrayEquals(a.z(), b.z(), 1e-12, p.id());
            if (++checked == 60) {
                break;
            }
        }
        assertTrue(checked > 20);
    }

    @Test
    void fewerMinutesMeansMoreShrinkageAndRangesContainTheCentre() {
        Projector pr = new Projector(DATA, new Cohorts(DATA, 900));
        pr.setRegressionMinutes(1500);
        String id = regular(Position.AM, 0);
        Projector.Projection p = pr.project(id, Position.AM, 2025, Projector.Method.MARCEL);
        Projector.Projection naive = pr.project(id, Position.AM, 2025, Projector.Method.MEAN3);
        int i = Stat.XA.ordinal();
        assertTrue(Math.abs(p.z()[i]) <= Math.abs(naive.z()[i]) + 0.6, "shrunk toward the median (plus aging)");
        for (int s = 0; s < Stat.COUNT; s++) {
            if (!Double.isNaN(p.low()[s])) {
                assertTrue(p.low()[s] <= p.z()[s] + 1e-12 && p.z()[s] <= p.high()[s] + 1e-12);
            }
        }
        pr.setRegressionMinutes(1_000_000); // overwhelming shrinkage: everything near the median
        assertTrue(Math.abs(pr.project(id, Position.AM, 2025, Projector.Method.MARCEL).z()[i]) < 0.6);
    }

    @Test
    void projectionViewNeverTurnsAMissingRangeIntoZero() {
        String id = regular(Position.AM, 0);
        Scout.ProjectionView v = SCOUT.projection(id, "AM");
        assertFalse(v.stats().isEmpty());
        for (Scout.ProjectedStat s : v.stats()) {
            assertTrue(Double.isNaN(s.low()) == Double.isNaN(s.high()), s.stat());
            if (!Double.isNaN(s.low())) {
                assertTrue(s.low() <= s.per90() + 1e-9 && s.per90() <= s.high() + 1e-9, s.stat());
            }
        }
    }

    @Test
    void edgeCasesProjection() {
        Projector pr = new Projector(DATA, new Cohorts(DATA, 900));
        assertThrows(NoSuchElementException.class, () -> pr.project("nobody", Position.AM, 2025,
                Projector.Method.BLEND));
        String retired = DATA.players().stream().filter(p -> DATA.seasonsOf(p.id()).getLast().season() < 2020)
                .findFirst().orElseThrow().id();
        assertNull(pr.project(retired, Position.CM, 2025, Projector.Method.BLEND), "no recent minutes: no projection");
        assertThrows(IllegalArgumentException.class, () -> pr.calibrate(0, 1, Projector.Method.MARCEL));
        assertThrows(IllegalArgumentException.class, () -> pr.calibrate(1000, 0, Projector.Method.MARCEL));
    }

    @Test
    void squadPlanAssignsDistinctPlayersAndNeverLosesToGreedyOnTheWeakestRole() {
        SquadPlanner planner = new SquadPlanner(DATA, SCOUT);
        List<String> leaving = List.of(regular(Position.AM, 0), regular(Position.AM, 1), regular(Position.W, 0));
        SquadPlanner.Result r = planner.plan(new SquadPlanner.Request(leaving, null, null, null, 5, null));
        Set<String> chosen = new HashSet<>();
        for (SquadPlanner.Assignment a : r.matching()) {
            assertTrue(a.candidateId() != null, "every role filled");
            assertTrue(chosen.add(a.candidateId()), "a player is signed once");
            assertFalse(leaving.contains(a.candidateId()), "departures are not their own replacements");
        }
        if (r.greedyFilled() == leaving.size()) {
            assertTrue(r.weakest() >= r.greedyWeakest() - 1e-9, r.weakest() + " vs greedy " + r.greedyWeakest());
        }
        assertEquals(r, planner.plan(new SquadPlanner.Request(leaving, null, null, null, 5, null)), "deterministic");
    }

    @Test
    void edgeCasesSquadPlanAndCompare() {
        SquadPlanner planner = new SquadPlanner(DATA, SCOUT);
        String a = regular(Position.CM, 0);
        String b = regular(Position.CM, 1);
        assertThrows(IllegalArgumentException.class, () -> planner.plan(new SquadPlanner.Request(List.of(), null, null,
                null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(new SquadPlanner.Request(List.of(a, a), null,
                null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(new SquadPlanner.Request(List.of(a), null,
                null, null, 0, null)));
        // nobody is 15: the role stays unfilled, reported rather than thrown
        SquadPlanner.Result none = planner.plan(new SquadPlanner.Request(List.of(a), 15, null, null, null, null));
        assertEquals(List.of(DATA.player(a).name()), none.unfilled());

        Comparer c = new Comparer(DATA, SCOUT);
        Comparer.Result r = c.compare(List.of(a, b), null);
        assertEquals(100, r.similarity()[0][0], 1e-9);
        assertEquals(r.similarity()[0][1], r.similarity()[1][0], 1e-9);
        assertThrows(IllegalArgumentException.class, () -> c.compare(List.of(a), null));
        assertThrows(IllegalArgumentException.class, () -> c.compare(List.of(a, a), null));
        List<String> four = new ArrayList<>(List.of(a, b, regular(Position.CM, 2), regular(Position.CM, 3)));
        assertThrows(IllegalArgumentException.class, () -> c.compare(four, null));
    }
}

package com.successor.engine.scouting;

import com.successor.engine.algo.Work;
import com.successor.engine.data.Dataset;
import com.successor.engine.data.SyntheticData;
import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerSearchTest {

    private static PlayerSeason season(String id, double minutes) {
        double[] s = PlayerSeason.emptyStats();
        s[Stat.MINUTES.ordinal()] = minutes;
        return new PlayerSeason(id, 2024, League.PREMIER_LEAGUE, "Club", Position.AM, EnumSet.of(Source.SYNTHETIC), s);
    }

    private static final Dataset SMALL = new Dataset(List.of(
            new Player("tm:1", "Kevin De Bruyne", List.of(), null, 1991, "", Position.AM, "", "", 0, 0),
            new Player("tm:2", "Rayan Cherki", List.of("Mathis Cherki"), null, 2003, "", Position.AM, "", "", 0, 0),
            new Player("tm:3", "Martin Ødegaard", List.of(), null, 1998, "", Position.AM, "", "", 0, 0),
            new Player("tm:4", "Kevin Mbabu", List.of(), null, 1995, "", Position.FB, "", "", 0, 0)),
            List.of(season("tm:1", 9000), season("tm:2", 3000), season("tm:3", 7000), season("tm:4", 4000)));

    @Test
    void accentsCaseAndAliasesAreFound() {
        PlayerSearch s = new PlayerSearch(SMALL);
        assertEquals("tm:3", s.search("ODE", 5).hits().getFirst().id());
        assertEquals("tm:1", s.search("bru", 5).hits().getFirst().id());
        PlayerSearch.Hit cherki = s.search("mathis", 5).hits().getFirst();
        assertEquals("tm:2", cherki.id());
        assertEquals("Mathis Cherki", cherki.matchedAlias());
        assertNull(s.search("rayan", 5).hits().getFirst().matchedAlias());
    }

    @Test
    void severalWordsMustAllMatchAndRankingIsByMinutes() {
        PlayerSearch s = new PlayerSearch(SMALL);
        List<PlayerSearch.Hit> kevins = s.search("kevin", 5).hits();
        assertEquals(List.of("tm:1", "tm:4"), kevins.stream().map(PlayerSearch.Hit::id).toList(), "9000' before 4000'");
        assertEquals(List.of("tm:1"), s.search("kev bru", 5).hits().stream().map(PlayerSearch.Hit::id).toList());
        assertEquals(0, s.search("kevin cherki", 5).matched());
    }

    @Test
    void exactNameBeatsALongerCareerWithTheSamePrefix() {
        Dataset d = new Dataset(List.of(
                new Player("tm:5", "Rodri", List.of(), null, 1996, "", Position.DM, "", "", 0, 0),
                new Player("tm:6", "Ricardo Rodríguez", List.of(), null, 1992, "", Position.FB, "", "", 0, 0)),
                List.of(season("tm:5", 1000), season("tm:6", 9000)));
        assertEquals("tm:5", new PlayerSearch(d).search("rodri", 5).hits().getFirst().id());
        assertEquals("tm:6", new PlayerSearch(d).search("rodr", 5).hits().getFirst().id(), "no exact word: minutes");
    }

    @Test
    void limitCapsTheHitsButCountsAllMatches() {
        PlayerSearch s = new PlayerSearch(SMALL);
        PlayerSearch.Result r = s.search("k", 1);
        assertEquals(1, r.hits().size());
        assertEquals(2, r.matched());
    }

    @Test
    void trieAgreesWithTheLinearScanBaseline() {
        Dataset d = SyntheticData.generate(2000, 2015, 8, 9);
        PlayerSearch s = new PlayerSearch(d);
        for (String q : List.of("a", "ra", "luca mo", "zz", "teo", "b")) {
            assertEquals(s.linearScanCount(q, Work.counter()), s.search(q, 100).matched(), q);
        }
    }

    @Test
    void edgeCaseBlankQueryAndBadLimitAreRejected() {
        PlayerSearch s = new PlayerSearch(SMALL);
        assertThrows(IllegalArgumentException.class, () -> s.search("", 5));
        assertThrows(IllegalArgumentException.class, () -> s.search("  ?! ", 5));
        assertThrows(IllegalArgumentException.class, () -> s.search("kev", 0));
        assertThrows(IllegalArgumentException.class, () -> s.search("kev", 101));
        assertEquals(0, new PlayerSearch(new Dataset(List.of(), List.of())).search("x", 5).matched());
    }
}

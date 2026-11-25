package com.successor.server.api;

import com.successor.engine.ScoutingEngine;
import com.successor.engine.data.Dataset;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Stat;
import com.successor.engine.scouting.PlayerSearch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player search and profiles.
 * <pre>
 *   GET /api/players/search?q=bru&amp;limit=10   type-ahead search (trie), ranked by career minutes (quickselect)
 *   GET /api/players/tm:88755                  profile: identity and every season with its stats
 * </pre>
 */
@RestController
@RequestMapping("/api/players")
public class PlayersController {
    private final ScoutingEngine engine;

    public PlayersController(ScoutingEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/search")
    public PlayerSearch.Result search(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "10") int limit) {
        return engine.search().search(q, limit);
    }

    /** A season as JSON: stats that were not measured are left out rather than sent as 0 or NaN. */
    public record SeasonView(int season, String label, String league, String club, String position, int age,
                             List<String> sources, Map<String, Double> stats) {
    }

    public record Profile(String id, String name, List<String> aliases, String birthDate, int birthYear,
                          String nation, String position, String subPosition, String foot, int heightCm,
                          long marketValueEur, List<SeasonView> seasons) {
    }

    @GetMapping("/{id}")
    public Profile profile(@PathVariable String id) {
        Dataset d = engine.data();
        Player p = d.player(id); // NoSuchElementException → 404
        List<SeasonView> seasons = d.seasonsOf(id).stream().map(s -> view(p, s)).toList();
        return new Profile(p.id(), p.name(), p.aliases(), p.birthDate() == null ? null : p.birthDate().toString(),
                p.birthYear(), p.nation(), p.position() == null ? null : p.position().name(), p.subPosition(),
                p.foot(), p.heightCm(), p.marketValueEur(), seasons);
    }

    static SeasonView view(Player p, PlayerSeason s) {
        Map<String, Double> stats = new LinkedHashMap<>();
        for (Stat st : Stat.values()) {
            if (s.has(st)) {
                stats.put(st.key(), s.get(st));
            }
        }
        return new SeasonView(s.season(), s.seasonLabel(), s.league().label(), s.club(),
                s.position() == null ? null : s.position().name(), p.ageAtSeason(s.season()),
                s.sources().stream().map(Enum::name).sorted().toList(), stats);
    }
}

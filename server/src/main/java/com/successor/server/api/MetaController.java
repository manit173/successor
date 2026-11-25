package com.successor.server.api;

import com.successor.engine.DatasetSummary;
import com.successor.engine.ScoutingEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** {@code GET /api/meta}: what the server is, what data it holds, and which algorithms drive which feature. */
@RestController
@RequestMapping("/api")
public class MetaController {
    private final ScoutingEngine engine;

    public MetaController(ScoutingEngine engine) {
        this.engine = engine;
    }

    public record Algorithm(String name, String unit, String usedFor, String baseline) {
    }

    /**
     * A league in the data; {@code big5} leagues are the comparison yardstick. {@code adjustment} summarises the
     * league-strength factors that convert its numbers to Big-5 level (null for the Big 5).
     */
    public record LeagueInfo(String label, String country, boolean big5, String adjustment) {
    }

    public record Meta(String name, String version, String java, DatasetSummary dataset, List<Algorithm> algorithms,
                       List<LeagueInfo> leagues) {
    }

    static final List<Algorithm> ALGORITHMS = List.of(
            new Algorithm("Trie", "Unit 4 · Text search", "Player search that ignores accents and case",
                    "Linear scan over all names"),
            new Algorithm("Randomized quickselect", "Unit 6 · Randomization (CO5)",
                    "Percentiles within a position; top-k most similar players",
                    "Full sort; median-of-medians; binary-heap top-k"),
            new Algorithm("Bloom filter", "Unit 6 · Randomization (CO5)",
                    "Duplicate pre-check while loading sources, confirmed exactly", "HashSet"),
            new Algorithm("Bipartite matching (max-flow)", "Unit 5 · Network flow",
                    "Linking players across sources; squad planner (maximise the weakest match)",
                    "Greedy assignment"));

    @GetMapping("/meta")
    public Meta meta() {
        DatasetSummary d = engine.summary();
        List<LeagueInfo> leagues = java.util.Arrays.stream(com.successor.engine.model.League.values())
                .filter(l -> d.leagues().contains(l.label()))
                .map(l -> new LeagueInfo(l.label(), l.country(), l.big5(),
                        engine.scout().cohorts().strength().headline(l))).toList();
        return new Meta("Successor", "0.1.0", System.getProperty("java.version"), d, ALGORITHMS, leagues);
    }
}

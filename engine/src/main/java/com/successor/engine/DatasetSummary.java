package com.successor.engine;

import java.util.List;

/**
 * What the engine has loaded: shown by the app's status bar and returned by {@code GET /api/meta}.
 *
 * @param players       distinct players after linking sources
 * @param playerSeasons player-season rows (one per player, club and season)
 * @param leagues       league names, e.g. "Premier League"
 * @param seasons       season labels, e.g. "2023/24", oldest first
 * @param status        "empty" until data is loaded, then "ready"
 * @param origin        where the data came from, e.g. "data/processed" or "synthetic sample"
 */
public record DatasetSummary(int players, int playerSeasons, List<String> leagues, List<String> seasons,
                             String status, String origin) {

    public DatasetSummary {
        leagues = List.copyOf(leagues);
        seasons = List.copyOf(seasons);
    }

    public static DatasetSummary empty() {
        return new DatasetSummary(0, 0, List.of(), List.of(), "empty", "none");
    }

    public DatasetSummary withOrigin(String origin) {
        return new DatasetSummary(players, playerSeasons, leagues, seasons, status, origin);
    }
}

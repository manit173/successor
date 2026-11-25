package com.successor.engine.data;

import com.successor.engine.DatasetSummary;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;

/** The linked dataset in memory: players and their seasons, indexed by player. Immutable after construction. */
public final class Dataset {
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final List<PlayerSeason> seasons;
    private final Map<String, List<PlayerSeason>> byPlayer = new HashMap<>();

    public Dataset(Collection<Player> players, Collection<PlayerSeason> seasons) {
        for (Player p : players) {
            if (this.players.putIfAbsent(p.id(), p) != null) {
                throw new IllegalArgumentException("duplicate player id " + p.id());
            }
        }
        List<PlayerSeason> sorted = new ArrayList<>(seasons);
        sorted.sort(Comparator.comparing(PlayerSeason::playerId).thenComparingInt(PlayerSeason::season)
                .thenComparing(PlayerSeason::club));
        for (PlayerSeason s : sorted) {
            if (!this.players.containsKey(s.playerId())) {
                throw new IllegalArgumentException("season for unknown player " + s.playerId());
            }
            byPlayer.computeIfAbsent(s.playerId(), k -> new ArrayList<>()).add(s);
        }
        this.seasons = List.copyOf(sorted);
    }

    public Collection<Player> players() {
        return players.values();
    }

    public List<PlayerSeason> seasons() {
        return seasons;
    }

    public Player player(String id) {
        Player p = players.get(id);
        if (p == null) {
            throw new NoSuchElementException("no player with id " + id);
        }
        return p;
    }

    public boolean hasPlayer(String id) {
        return players.containsKey(id);
    }

    /** The player's seasons, oldest first (one per club when he moved mid-season). */
    public List<PlayerSeason> seasonsOf(String playerId) {
        return byPlayer.getOrDefault(playerId, List.of());
    }

    public DatasetSummary summary() {
        TreeSet<Integer> years = new TreeSet<>();
        TreeSet<String> leagues = new TreeSet<>();
        for (PlayerSeason s : seasons) {
            years.add(s.season());
            leagues.add(s.league().label());
        }
        List<String> labels = years.stream().map(PlayerSeason::label).toList();
        return new DatasetSummary(players.size(), seasons.size(), List.copyOf(leagues), labels,
                players.isEmpty() ? "empty" : "ready", "memory");
    }
}

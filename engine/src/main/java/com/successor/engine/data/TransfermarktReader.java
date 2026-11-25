package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Transfermarkt dataset (dcaribou/transfermarkt-datasets, CC0): player profiles and per-game appearances,
 * aggregated into one row per player, club and league-season.
 *
 * <p>Some leagues (Austria, Switzerland, …) have matches but no appearance rows. For a game without appearances, each
 * player's line is rebuilt from the lineup and the match events: a starter plays from 0', a substitute from the minute
 * he came on, until he is substituted or sent off (else 90'); goals (not own goals), assists and cards come from the
 * events, a second yellow counting as a yellow card as Transfermarkt's appearances do. Checked against 191,352
 * player-games that have both: minutes within 0.10% in total, goals exact, assists 99.6%, cards 99.9% identical.
 */
public final class TransfermarktReader {
    private TransfermarktReader() {
    }

    /** A Transfermarkt profile. */
    public record TmPlayer(String id, String name, LocalDate birthDate, String nation, String position,
                           String subPosition, String foot, int heightCm, long marketValueEur) {
    }

    /**
     * @param maxGames per "LEAGUE|season": the most league games any club played (34 in Portugal, 40 in Belgium with
     *                 its play-offs), the bound for one player's minutes in that league-season
     */
    public record Result(Map<String, TmPlayer> players, List<SourceRow> rows, Map<String, Integer> maxGames) {
    }

    private record Game(League league, int season) {
    }

    /** One player's line in one game. */
    record Line(String player, String name, String club, double minutes, double goals, double assists,
                        double yellow, double red) {
    }

    private static final class Acc {
        String player;
        String name;
        String club;
        League league;
        int season;
        double minutes;
        double goals;
        double assists;
        double yellow;
        double red;
        int games;
    }

    /** @param dir the unzipped dataset folder (players.csv.gz, games.csv.gz, appearances.csv.gz, clubs.csv.gz) */
    public static Result read(Path dir, int firstSeason, int lastSeason) {
        Map<String, Game> games = new HashMap<>();
        Map<String, String> clubNames = new HashMap<>();
        Map<String, Integer> clubGames = new HashMap<>();
        Csv.read(dir.resolve("games.csv.gz"), r -> {
            League l = League.fromTransfermarkt(r.get("competition_id"));
            int season = (int) r.num("season");
            if (l != null && season >= firstSeason && season <= lastSeason) {
                games.put(r.get("game_id"), new Game(l, season));
                clubGames.merge(l.name() + "|" + season + "|" + r.get("home_club_id"), 1, Integer::sum);
                clubGames.merge(l.name() + "|" + season + "|" + r.get("away_club_id"), 1, Integer::sum);
                clubNames.putIfAbsent(r.get("home_club_id"), r.get("home_club_name"));
                clubNames.putIfAbsent(r.get("away_club_id"), r.get("away_club_name"));
            }
        });
        Csv.read(dir.resolve("clubs.csv.gz"), r -> {
            if (clubNames.containsKey(r.get("club_id")) && !r.get("name").isBlank()) {
                clubNames.put(r.get("club_id"), r.get("name"));
            }
        });

        Map<String, Acc> acc = new LinkedHashMap<>();
        java.util.Set<String> withAppearances = new java.util.HashSet<>();
        Csv.read(dir.resolve("appearances.csv.gz"), r -> {
            Game g = games.get(r.get("game_id"));
            if (g == null) {
                return;
            }
            withAppearances.add(r.get("game_id"));
            add(acc, g, new Line(r.get("player_id"), r.get("player_name"), r.get("player_club_id"),
                    zero(r.num("minutes_played")), zero(r.num("goals")), zero(r.num("assists")),
                    zero(r.num("yellow_cards")), zero(r.num("red_cards"))));
        });
        Path lineups = dir.resolve("game_lineups.csv.gz");
        Path events = dir.resolve("game_events.csv.gz");
        if (java.nio.file.Files.exists(lineups) && java.nio.file.Files.exists(events)) {
            for (Map.Entry<String, List<Line>> e : rebuilt(games.keySet(), withAppearances, lineups, events)
                    .entrySet()) {
                Game g = games.get(e.getKey());
                e.getValue().forEach(line -> add(acc, g, line));
            }
        }

        Map<String, TmPlayer> players = new HashMap<>();
        java.util.Set<String> wanted = new java.util.HashSet<>();
        acc.values().forEach(a -> wanted.add(a.player));
        Csv.read(dir.resolve("players.csv.gz"), r -> {
            String id = r.get("player_id");
            if (!wanted.contains(id)) {
                return;
            }
            players.put(id, new TmPlayer(id, r.get("name"), date(r.get("date_of_birth")),
                    r.get("country_of_citizenship"), r.get("position"), r.get("sub_position"), r.get("foot"),
                    (int) zero(r.num("height_in_cm")), (long) zero(r.num("market_value_in_eur"))));
        });

        List<SourceRow> rows = new ArrayList<>(acc.size());
        for (Acc a : acc.values()) {
            TmPlayer p = players.get(a.player);
            double[] s = com.successor.engine.model.PlayerSeason.emptyStats();
            s[Stat.MINUTES.ordinal()] = a.minutes;
            s[Stat.GAMES.ordinal()] = a.games;
            s[Stat.GOALS.ordinal()] = a.goals;
            s[Stat.ASSISTS.ordinal()] = a.assists;
            s[Stat.YELLOW_CARDS.ordinal()] = a.yellow;
            s[Stat.RED_CARDS.ordinal()] = a.red;
            int birthYear = p != null && p.birthDate() != null ? p.birthDate().getYear() : 0;
            rows.add(new SourceRow(Source.TRANSFERMARKT, a.player, p != null ? p.name() : a.name, birthYear, a.season,
                    a.league, a.club, clubNames.getOrDefault(a.club, a.club), p != null ? p.subPosition() : "", s));
        }
        Map<String, Integer> maxGames = new HashMap<>();
        clubGames.forEach((k, n) -> maxGames.merge(k.substring(0, k.lastIndexOf('|')), n, Math::max));
        return new Result(players, rows, maxGames);
    }

    private static void add(Map<String, Acc> acc, Game g, Line line) {
        Acc a = acc.computeIfAbsent(line.player() + "|" + line.club() + "|" + g.season, k -> {
            Acc n = new Acc();
            n.player = line.player();
            n.name = line.name();
            n.club = line.club();
            n.league = g.league;
            n.season = g.season;
            return n;
        });
        a.minutes += line.minutes();
        a.goals += line.goals();
        a.assists += line.assists();
        a.yellow += line.yellow();
        a.red += line.red();
        a.games++;
    }

    record Event(int minute, String type, String player, String playerIn, String assist, String description) {
    }

    /** Lines rebuilt from lineups and events for the wanted games that have no appearance rows. */
    static Map<String, List<Line>> rebuilt(java.util.Set<String> wanted, java.util.Set<String> skip, Path lineups,
                                          Path events) {
        Map<String, List<String[]>> squad = new HashMap<>(); // game → {player, name, club, type}
        Csv.read(lineups, r -> {
            String g = r.get("game_id");
            if (wanted.contains(g) && !skip.contains(g)) {
                squad.computeIfAbsent(g, k -> new ArrayList<>())
                        .add(new String[]{r.get("player_id"), r.get("player_name"), r.get("club_id"), r.get("type")});
            }
        });
        Map<String, List<Event>> ev = new HashMap<>();
        Csv.read(events, r -> {
            String g = r.get("game_id");
            if (squad.containsKey(g)) {
                ev.computeIfAbsent(g, k -> new ArrayList<>()).add(new Event((int) zero(r.num("minute")), r.get("type"),
                        r.get("player_id"), r.get("player_in_id"), r.get("player_assist_id"),
                        r.get("description").toLowerCase(java.util.Locale.ROOT)));
            }
        });
        Map<String, List<Line>> out = new HashMap<>();
        squad.forEach((g, players) -> out.put(g, rebuildGame(players, ev.getOrDefault(g, List.of()))));
        return out;
    }

    static List<Line> rebuildGame(List<String[]> players, List<Event> events) {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(java.util.Comparator.comparingInt(Event::minute));
        Map<String, Integer> on = new HashMap<>();
        Map<String, Integer> off = new HashMap<>();
        for (String[] p : players) {
            if ("starting_lineup".equals(p[3])) {
                on.put(p[0], 0);
            }
        }
        for (Event e : sorted) {
            int m = Math.max(0, Math.min(90, e.minute()));
            if ("Substitutions".equals(e.type())) {
                if (!e.player().isEmpty()) {
                    off.putIfAbsent(e.player(), m);
                }
                if (!e.playerIn().isEmpty()) {
                    on.putIfAbsent(e.playerIn(), m);
                }
            } else if ("Cards".equals(e.type()) && sentOff(e.description())) {
                off.putIfAbsent(e.player(), m);
            }
        }
        Map<String, double[]> counts = new HashMap<>(); // goals, assists, yellow, red
        for (Event e : events) {
            if ("Goals".equals(e.type()) && !e.description().contains("own")) {
                counts.computeIfAbsent(e.player(), k -> new double[4])[0]++;
                if (!e.assist().isEmpty()) {
                    counts.computeIfAbsent(e.assist(), k -> new double[4])[1]++;
                }
            } else if ("Cards".equals(e.type())) {
                boolean straightRed = e.description().contains("red card");
                boolean yellow = !straightRed && e.description().contains("yellow");
                if (straightRed || yellow) {
                    counts.computeIfAbsent(e.player(), k -> new double[4])[straightRed ? 3 : 2]++;
                }
            }
        }
        List<Line> lines = new ArrayList<>();
        for (String[] p : players) {
            Integer start = on.get(p[0]);
            if (start == null) {
                continue; // an unused substitute
            }
            double[] c = counts.getOrDefault(p[0], new double[4]);
            lines.add(new Line(p[0], p[1], p[2], Math.max(0, off.getOrDefault(p[0], 90) - start), c[0], c[1], c[2],
                    c[3]));
        }
        return lines;
    }

    /** "Red card" or "Second yellow" end a player's game. */
    private static boolean sentOff(String description) {
        return description.contains("red card") || description.contains("second yellow");
    }

    private static double zero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static LocalDate date(String s) {
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}

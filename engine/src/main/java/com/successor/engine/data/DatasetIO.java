package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes the processed dataset: {@code players.csv} and {@code player_seasons.csv}. Invalid rows are
 * rejected with the file and line number rather than loaded half-right.
 */
public final class DatasetIO {
    private DatasetIO() {
    }

    public static final String PLAYERS = "players.csv";
    public static final String SEASONS = "player_seasons.csv";

    private static final String[] PLAYER_COLS = {"id", "name", "aliases", "birth_date", "birth_year", "nation",
            "position", "sub_position", "foot", "height_cm", "market_value_eur"};
    private static final String[] SEASON_COLS = {"player_id", "season", "league", "club", "position", "sources"};

    public static void write(Dataset d, Path dir) {
        try {
            Files.createDirectories(dir);
            try (BufferedWriter w = Files.newBufferedWriter(dir.resolve(PLAYERS), StandardCharsets.UTF_8)) {
                w.write(String.join(",", PLAYER_COLS));
                w.newLine();
                for (Player p : d.players()) {
                    w.write(String.join(",", Csv.quote(p.id()), Csv.quote(p.name()),
                            Csv.quote(String.join("|", p.aliases())),
                            p.birthDate() == null ? "" : p.birthDate().toString(),
                            p.birthYear() == 0 ? "" : Integer.toString(p.birthYear()), Csv.quote(p.nation()),
                            p.position() == null ? "" : p.position().name(), Csv.quote(p.subPosition()),
                            Csv.quote(p.foot()), p.heightCm() == 0 ? "" : Integer.toString(p.heightCm()),
                            p.marketValueEur() == 0 ? "" : Long.toString(p.marketValueEur())));
                    w.newLine();
                }
            }
            try (BufferedWriter w = Files.newBufferedWriter(dir.resolve(SEASONS), StandardCharsets.UTF_8)) {
                List<String> header = new ArrayList<>(Arrays.asList(SEASON_COLS));
                for (Stat s : Stat.values()) {
                    header.add(s.key());
                }
                w.write(String.join(",", header));
                w.newLine();
                StringBuilder sb = new StringBuilder();
                for (PlayerSeason s : d.seasons()) {
                    sb.setLength(0);
                    sb.append(Csv.quote(s.playerId())).append(',').append(s.season()).append(',')
                            .append(s.league().name()).append(',').append(Csv.quote(s.club())).append(',')
                            .append(s.position() == null ? "" : s.position().name()).append(',')
                            .append(String.join("|", s.sources().stream().map(Enum::name).sorted().toList()));
                    for (Stat st : Stat.values()) {
                        sb.append(',');
                        double v = s.get(st);
                        if (!Double.isNaN(v)) {
                            sb.append(v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v)
                                    : String.format(java.util.Locale.ROOT, "%.4f", v));
                        }
                    }
                    w.write(sb.toString());
                    w.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write dataset to " + dir, e);
        }
    }

    public static Dataset read(Path dir) {
        Path pf = dir.resolve(PLAYERS);
        Path sf = dir.resolve(SEASONS);
        if (!Files.isRegularFile(pf) || !Files.isRegularFile(sf)) {
            throw new IllegalArgumentException("no dataset in " + dir.toAbsolutePath() + " (expected " + PLAYERS
                    + " and " + SEASONS + ")");
        }
        List<Player> players = new ArrayList<>();
        Csv.read(pf, r -> {
            String id = r.get("id").trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException(pf + ":" + r.line() + ": player without id");
            }
            String aliases = r.get("aliases");
            players.add(new Player(id, r.get("name"), aliases.isEmpty() ? List.of() : List.of(aliases.split("\\|")),
                    r.get("birth_date").isEmpty() ? null : LocalDate.parse(r.get("birth_date")),
                    (int) zero(r.num("birth_year")), r.get("nation"),
                    r.get("position").isEmpty() ? null : Position.valueOf(r.get("position")), r.get("sub_position"),
                    r.get("foot"), (int) zero(r.num("height_cm")), (long) zero(r.num("market_value_eur"))));
        });
        List<PlayerSeason> seasons = new ArrayList<>();
        Csv.read(sf, r -> {
            League league = League.valueOf(r.get("league"));
            double season = r.num("season");
            if (Double.isNaN(season)) {
                throw new IllegalArgumentException(sf + ":" + r.line() + ": missing season");
            }
            Set<Source> sources = EnumSet.noneOf(Source.class);
            for (String s : r.get("sources").split("\\|")) {
                if (!s.isBlank()) {
                    sources.add(Source.valueOf(s));
                }
            }
            double[] stats = PlayerSeason.emptyStats();
            for (Stat st : Stat.values()) {
                stats[st.ordinal()] = r.num(st.key());
            }
            seasons.add(new PlayerSeason(r.get("player_id"), (int) season, league, r.get("club"),
                    r.get("position").isEmpty() ? null : Position.valueOf(r.get("position")), sources, stats));
        });
        return new Dataset(players, seasons);
    }

    private static double zero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }
}

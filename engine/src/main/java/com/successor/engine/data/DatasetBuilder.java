package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the processed dataset from the raw sources (see data/SOURCES.md):
 * read → drop duplicate rows (Bloom pre-check + exact confirmation) → link to Transfermarkt players
 * ({@link Linker}) → merge each player's club-season into one row → write {@code data/processed/} and a linking
 * report.
 *
 * <p>Run: {@code java -cp engine/target/classes com.successor.engine.data.DatasetBuilder data/raw data/processed}
 */
public final class DatasetBuilder {
    public static final int FIRST_SEASON = 2012;
    public static final int LAST_UNDERSTAT_SEASON = 2023; // the mirror's 2024/25 stops after five weeks
    /** League games FBref counts but Transfermarkt's league data does not: Belgium's play-offs (up to 10). */
    static final Map<League, Integer> PLAY_OFF_GAMES_NOT_IN_TM = Map.of(League.BELGIAN_PRO_LEAGUE, 10);
    /**
     * How far an archive row's minutes may differ from Transfermarkt's at that club. Measured on the 12,559 linked
     * rows of players with one club that season (Belgium aside): median difference 0, 90th percentile +7, 99th +151
     * minutes; beyond that lie the merged rows.
     */
    static final double ARCHIVE_TOLERANCE = 150;

    private final StringBuilder report = new StringBuilder();

    public static void main(String[] args) {
        Path raw = Path.of(args.length > 0 ? args[0] : "data/raw");
        Path out = Path.of(args.length > 1 ? args[1] : "data/processed");
        long t0 = System.nanoTime();
        DatasetBuilder b = new DatasetBuilder();
        Dataset d = b.build(raw);
        DatasetIO.write(d, out);
        com.successor.engine.scouting.Projector.Backtest bt =
                com.successor.engine.ScoutingEngine.writeCalibration(d, out);
        b.line("");
        b.line("## Projection calibration");
        b.line("");
        b.line(String.format(Locale.ROOT, "Fitted on target seasons %s: R = %.0f minutes, range scale %.1f, method %s.",
                bt.trainSeasons(), bt.regressionMinutes(), bt.rangeScale(), bt.chosenMethod()));
        for (var m : bt.overall()) {
            b.line(String.format(Locale.ROOT, "- %s: MAE %.3f on %s (%+.1f%% vs naive), 80%% range coverage %.1f%%",
                    m.method(), m.mae(), bt.testSeasons(), m.improvementVsNaive(), m.coverage80()));
        }
        var lc = bt.leagueCheck();
        b.line("");
        b.line("## League strength");
        b.line("");
        b.line(String.format(Locale.ROOT, "Backtest with factors from %s (%s): all test players MAE %.3f "
                        + "(without factors %.3f); %d arrivals from other leagues MAE %.3f (without factors %.3f).",
                lc.factorsFrom(), lc.method(), lc.overallMae(), lc.overallMaeUnadjusted(), lc.arrivals(),
                lc.arrivalsMae(), lc.arrivalsMaeUnadjusted()));
        b.line("");
        b.line("Factors used by the app (every transfer in the data; raw = before shrinkage, movers = transfers with "
                + "the stat measured):");
        b.line("");
        b.line("| League | Goals (np) | Assists | xG | Key passes | Prog passes | Tackles | Interceptions | Blocks |");
        b.line("| --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        var ls = com.successor.engine.scouting.LeagueStrength.estimateAll(d,
                com.successor.engine.scouting.Scout.DEFAULT_MIN_MINUTES);
        for (League l : League.values()) {
            if (l.big5()) {
                continue;
            }
            StringBuilder row = new StringBuilder("| " + l.label());
            for (Stat st : new Stat[]{Stat.NP_GOALS, Stat.ASSISTS, Stat.XG, Stat.KEY_PASSES, Stat.PROG_PASSES,
                    Stat.TACKLES, Stat.INTERCEPTIONS, Stat.BLOCKS}) {
                var e = ls.estimates().stream().filter(x -> x.league() == l && x.stat() == st).findFirst();
                row.append(" | ").append(e.map(x -> String.format(Locale.ROOT, "%.2f%s", x.factor(),
                        Double.isNaN(x.raw()) ? " (prior)" : String.format(Locale.ROOT, " (raw %.2f, n %d)", x.raw(),
                                x.movers()))).orElse("–"));
            }
            b.line(row.append(" |").toString());
        }
        b.line("");
        b.line(String.format(Locale.ROOT, "Built in %.1f s.", (System.nanoTime() - t0) / 1e9));
        try {
            Files.writeString(out.resolve("link_report.md"), b.report());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(b.report());
        System.out.println("wrote " + d.players().size() + " players and " + d.seasons().size() + " player-seasons to "
                + out);
    }

    public String report() {
        return report.toString();
    }

    private void line(String s) {
        report.append(s).append('\n');
    }

    public Dataset build(Path raw) {
        line("# Linking report");
        line("");
        TransfermarktReader.Result tm = TransfermarktReader.read(raw.resolve("transfermarkt"), FIRST_SEASON, 2025);
        List<SourceRow> us = UnderstatReader.read(raw.resolve("kaggle-understat"), 2014, LAST_UNDERSTAT_SEASON);
        List<SourceRow> fb24 = FbrefReader.read(raw.resolve("kaggle-fbref-2425/players_data-2024_2025.csv"), 2024,
                Source.FBREF);
        List<SourceRow> fb25 = FbrefReader.read(raw.resolve("kaggle-fbref-2526/players_data-2025_2026.csv"), 2025,
                Source.FBREF_BASIC);
        // earlier full FBref seasons (optional: built without them if not downloaded)
        List<SourceRow> fb21 = optional(raw.resolve("kaggle-fbref-2122/2021-2022 Football Player Stats.csv"),
                FbrefSeasons::read2122);
        List<SourceRow> fb22 = optional(raw.resolve("kaggle-fbref-2223"), FbrefSeasons::read2223);
        List<SourceRow> fb23 = optional(raw.resolve("kaggle-fbref-2324/normalized"), FbrefSeasons::read2324);
        // the FBref archive only fills league-seasons that no fuller export covers
        Set<String> covered = new java.util.HashSet<>();
        for (List<SourceRow> full : List.of(fb21, fb22, fb23, fb24)) {
            full.forEach(r -> covered.add(r.league().name() + "|" + r.season()));
        }
        List<SourceRow> archive = optional(raw.resolve("kaggle-fbref-archive/fifa_fbref_merged.csv"),
                p -> SoccerdataSets.readArchive(p, covered));
        List<SourceRow> sofa = optional(raw.resolve("kaggle-big5-2526/PERFECT_scouting_data_2026.csv"),
                SoccerdataSets::read2526);

        // 1. duplicates
        int total = tm.rows().size() + us.size() + archive.size() + fb21.size() + fb22.size() + fb23.size()
                + fb24.size() + fb25.size() + sofa.size();
        Deduper dedup = new Deduper(total, 0.01);
        List<SourceRow> tmRows = unique(tm.rows(), dedup);
        // order matters when merging: later sources override earlier ones for the same stat, so a season with both
        // Understat and FBref ends up with Opta's numbers wherever FBref measured the stat. The archive comes before
        // Understat: it covers only some Big-5 players, and one season's cohort must not mix two xG models, so
        // Understat keeps xG, shots and key passes there and the archive only fills what Understat does not measure.
        List<SourceRow> sources = new ArrayList<>();
        sources.addAll(unique(archive, dedup));
        sources.addAll(unique(us, dedup));
        sources.addAll(unique(fb21, dedup));
        sources.addAll(unique(fb22, dedup));
        sources.addAll(unique(fb23, dedup));
        sources.addAll(unique(fb24, dedup));
        sources.addAll(unique(fb25, dedup));
        sources.addAll(unique(sofa, dedup));
        line("## Rows read");
        line("");
        line("| Source | Seasons | Rows |");
        line("| --- | --- | --- |");
        line("| Transfermarkt (appearances; lineups + events where a league has none) | " + FIRST_SEASON + "/" + (FIRST_SEASON + 1) % 100 + "–2025/26 | " + tm.rows().size() + " |");
        line("| Understat | 2014/15–" + PlayerSeason.label(LAST_UNDERSTAT_SEASON) + " | " + us.size() + " |");
        line("| FBref archive (per 90 → totals; leagues/seasons not covered below) | 2017/18–2024/25 | "
                + archive.size() + " |");
        line("| FBref (StatsBomb era, per 90 → totals) | 2021/22 | " + fb21.size() + " |");
        line("| FBref (full) | 2022/23 | " + fb22.size() + " |");
        line("| FBref (full) | 2023/24 | " + fb23.size() + " |");
        line("| FBref (full) | 2024/25 | " + fb24.size() + " |");
        line("| FBref (basic) | 2025/26 | " + fb25.size() + " |");
        line("| Understat + Sofascore (Big 5) | 2025/26 | " + sofa.size() + " |");
        line("");
        line(String.format(Locale.ROOT, "**Duplicates (Bloom pre-check, exact confirmation):** %,d keys checked; "
                        + "%,d proved new by the Bloom filter with no exact look-up; %,d duplicate rows dropped; "
                        + "%,d Bloom false positives caught by the exact check (filter: %,d bits, k = %d, %.1f KiB).",
                total, dedup.definitelyNew(), dedup.duplicates(), dedup.falsePositives(), dedup.bloom().bitSize(),
                dedup.bloom().hashCount(), dedup.bloom().sizeBytes() / 1024.0));
        line("");

        // 2. linking
        Linker.Result link = Linker.link(tmRows, sources);
        reportLinking(link, sources);

        // 3. merge into one row per player, club and season
        Map<String, Stint> stints = new LinkedHashMap<>();
        Map<String, String> shortClub = new HashMap<>(); // league|season|tmClubKey → source's short club name
        Map<String, String> shortClubAnySeason = new HashMap<>(); // tmClubKey → latest short name seen
        link.clubLinks().values().forEach(c -> {
            shortClub.merge(c.league().name() + "|" + c.season() + "|" + c.tmClubKey(), c.sourceClub(), (a, b) -> a);
            shortClubAnySeason.put(c.tmClubKey(), c.sourceClub());
        });
        for (SourceRow r : tmRows) {
            Stint s = stints.computeIfAbsent(stintKey("tm:" + r.key(), r.league(), r.season(), r.clubKey()),
                    k -> new Stint("tm:" + r.key(), r));
            s.merge(r);
        }
        // The archive collapsed each player-season into one row: a mid-season transfer's two clubs (even two
        // leagues) are added up under one club, and two namesakes born the same year are added together. Its rows
        // are therefore used only when they link to a Transfermarkt player and agree with his minutes at that club:
        // within ARCHIVE_TOLERANCE, plus, for a player with one club that season, the play-off games Transfermarkt's
        // league data leaves out (Belgium), and never more minutes than the league's season holds.
        Set<SourceRow> archiveRows = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        archiveRows.addAll(archive);
        Map<String, Integer> tmStintsInSeason = new HashMap<>();
        tmRows.forEach(r -> tmStintsInSeason.merge(r.key() + "|" + r.season(), 1, Integer::sum));
        int archiveUnlinked = 0;
        int archiveDisagree = 0;
        Map<String, int[]> archiveByLeague = new java.util.TreeMap<>(); // league → {used, dropped}
        Map<String, Set<String>> aliases = new HashMap<>();
        Map<String, SourceRow> unlinkedIdentity = new LinkedHashMap<>();
        for (SourceRow r : sources) {
            String tmId = link.tmIdOf().get(r);
            String tmClub = link.clubKeyFor(r);
            if (archiveRows.contains(r)) {
                Stint tmStint = tmId == null || tmClub == null ? null
                        : stints.get(stintKey("tm:" + tmId, r.league(), r.season(), tmClub));
                if (tmStint == null) {
                    archiveUnlinked++;
                    continue;
                }
                double tmMinutes = tmStint.stats[Stat.MINUTES.ordinal()];
                int[] tally = archiveByLeague.computeIfAbsent(r.league().label(), k -> new int[2]);
                boolean oneClub = tmStintsInSeason.getOrDefault(tmId + "|" + r.season(), 0) == 1;
                int playOffs = oneClub ? PLAY_OFF_GAMES_NOT_IN_TM.getOrDefault(r.league(), 0) : 0;
                int seasonGames = tm.maxGames().getOrDefault(r.league().name() + "|" + r.season(), 38) + playOffs;
                double m = r.stats()[Stat.MINUTES.ordinal()];
                double gap = m - tmMinutes;
                if (gap < -ARCHIVE_TOLERANCE || gap > ARCHIVE_TOLERANCE + 90 * playOffs || m > 90 * seasonGames + 120) {
                    archiveDisagree++;
                    tally[1]++;
                    continue;
                }
                tally[0]++;
            }
            String playerId;
            String clubKey;
            if (tmId != null) {
                playerId = "tm:" + tmId;
                clubKey = tmClub != null ? tmClub : r.clubKey();
                Player tmp = toPlayer(tm.players().get(tmId), tmId);
                if (!Names.normalize(r.name()).equals(Names.normalize(tmp.name()))) {
                    aliases.computeIfAbsent(playerId, k -> new LinkedHashSet<>()).add(r.name());
                }
            } else {
                playerId = r.source() == Source.UNDERSTAT ? "us:" + r.key()
                        : "fb:" + Names.normalize(r.name()).replace(' ', '-') + ":" + r.birthYear();
                clubKey = r.clubKey();
                unlinkedIdentity.putIfAbsent(playerId, r);
            }
            Stint s = stints.computeIfAbsent(stintKey(playerId, r.league(), r.season(), clubKey),
                    k -> new Stint(playerId, r));
            s.merge(r);
        }

        line(String.format(Locale.ROOT, "FBref archive rows not used: %,d without a linked Transfermarkt club-season, "
                + "%,d whose minutes differ from Transfermarkt's at that club by more than "
                + "150 (a mid-season transfer or two namesakes added together, or a wrong link). Used / dropped by league: %s.", archiveUnlinked, archiveDisagree,
                String.join(", ", archiveByLeague.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0]
                        + "/" + e.getValue()[1]).toList())));
        line("");

        // 4. players
        List<Player> players = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        stints.values().forEach(s -> used.add(s.playerId));
        for (String id : used) {
            if (id.startsWith("tm:")) {
                Player p = toPlayer(tm.players().get(id.substring(3)), id.substring(3));
                players.add(new Player(id, p.name(), new ArrayList<>(aliases.getOrDefault(id, Set.of())),
                        p.birthDate(), p.birthYear(), p.nation(), p.position(), p.subPosition(), p.foot(), p.heightCm(),
                        p.marketValueEur()));
            } else {
                SourceRow r = unlinkedIdentity.get(id);
                Position pos = r.source() == Source.UNDERSTAT ? Position.fromUnderstat(r.positionCode())
                        : fbrefPosition(r.positionCode());
                players.add(new Player(id, r.name(), List.of(), null, r.birthYear(), "", pos, r.positionCode(), "", 0,
                        0));
            }
        }
        Map<String, Player> byId = new HashMap<>();
        players.forEach(p -> byId.put(p.id(), p));

        List<PlayerSeason> seasons = new ArrayList<>(stints.size());
        for (Stint s : stints.values()) {
            Player p = byId.get(s.playerId);
            Position pos = s.position != null ? s.position : p.position();
            // the source's short club name ("Wolfsburg"), also for seasons only Transfermarkt covers
            String club = shortClub.getOrDefault(s.league.name() + "|" + s.season + "|" + s.clubKey,
                    shortClubAnySeason.getOrDefault(s.clubKey, s.club));
            seasons.add(new PlayerSeason(s.playerId, s.season, s.league, club, pos, s.sources, s.stats));
        }
        Dataset d = new Dataset(players, seasons);
        line("## Checks");
        line("");
        List<String> odd = new ArrayList<>();
        for (Player p : d.players()) {
            for (String a : p.aliases()) {
                if (Names.personSimilarity(a, p.name()) < 0.6) {
                    odd.add(p.name() + " ← " + a);
                }
            }
        }
        Map<String, Double> perSeason = new HashMap<>();
        for (PlayerSeason s : d.seasons()) {
            perSeason.merge(s.playerId() + "|" + s.league().name() + "|" + s.season(), s.minutes(), Double::sum);
        }
        List<String> impossible = new ArrayList<>();
        perSeason.forEach((k, m) -> {
            String[] parts = k.split("\\|");
            int games = tm.maxGames().getOrDefault(parts[1] + "|" + parts[2], 38)
                    + PLAY_OFF_GAMES_NOT_IN_TM.getOrDefault(League.valueOf(parts[1]), 0);
            if (m > games * 90 + 120) {
                impossible.add(byId.get(parts[0]).name() + " " + parts[1] + " " + parts[2] + " (" + Math.round(m)
                        + " min)");
            }
        });
        line(String.format(Locale.ROOT, "- Player-seasons with more minutes in one league than its season holds "
                        + "(the most games any club played × 90 + stoppage): **%d**%s (a sign of two players merged).",
                impossible.size(), impossible.isEmpty() ? "" : ": " + String.join("; ",
                        impossible.stream().limit(15).toList())));
        line(String.format(Locale.ROOT, "- Linked spellings that look unlike the player's name: **%d**%s", odd.size(),
                odd.isEmpty() ? "." : " (" + String.join("; ", odd.stream().limit(20).toList()) + ")."));
        line("");
        line("## Result");
        line("");
        line(String.format(Locale.ROOT, "%,d players, %,d player-seasons (one per player, club and season).",
                d.players().size(), d.seasons().size()));
        return d;
    }

    private void reportLinking(Linker.Result link, List<SourceRow> sources) {
        line("## Linking to Transfermarkt players");
        line("");
        line("| Source | Rows | Linked | Share |");
        line("| --- | --- | --- | --- |");
        for (Source src : new Source[]{Source.UNDERSTAT, Source.FBREF, Source.FBREF_BASIC, Source.SOFASCORE}) {
            long n = sources.stream().filter(r -> r.source() == src).count();
            long l = sources.stream().filter(r -> r.source() == src && link.tmIdOf().containsKey(r)).count();
            long minutes = Math.round(sources.stream().filter(r -> r.source() == src)
                    .mapToDouble(r -> zero(r.stats()[Stat.MINUTES.ordinal()])).sum());
            long linkedMinutes = Math.round(sources.stream()
                    .filter(r -> r.source() == src && link.tmIdOf().containsKey(r))
                    .mapToDouble(r -> zero(r.stats()[Stat.MINUTES.ordinal()])).sum());
            line(String.format(Locale.ROOT, "| %s | %,d | %,d | %.1f%% of rows, %.1f%% of minutes |", src, n, l,
                    100.0 * l / Math.max(1, n), 100.0 * linkedMinutes / Math.max(1, minutes)));
        }
        line("");
        line(String.format(Locale.ROOT, "Clubs linked: %,d league-season clubs; %d by elimination (the last unmatched "
                        + "pair in a league-season); %d unmatched. Matching work: %,d edge scans. %d source ids "
                        + "linked to different players in different seasons (kept the majority; %d rows changed).",
                link.clubLinks().size(), link.clubLinks().values().stream().filter(Linker.ClubLink::byElimination)
                        .count(), link.unmatchedClubs().size(), link.matchingWork(), link.conflicts(),
                link.reassignedByMajority()));
        line("");
        List<String> elim = link.clubLinks().values().stream().filter(Linker.ClubLink::byElimination)
                .map(c -> c.league().label() + " " + PlayerSeason.label(c.season()) + ": " + c.sourceClub() + " → "
                        + c.tmClub()).distinct().limit(15).toList();
        if (!elim.isEmpty()) {
            line("Paired by elimination: " + String.join("; ", elim) + ".");
            line("");
        }
        if (!link.unmatchedClubs().isEmpty()) {
            line("Unmatched clubs: " + String.join("; ", link.unmatchedClubs()) + ".");
            line("");
        }
    }

    private static List<SourceRow> optional(Path p, java.util.function.Function<Path, List<SourceRow>> reader) {
        return Files.exists(p) ? reader.apply(p) : List.of();
    }

    private static List<SourceRow> unique(List<SourceRow> rows, Deduper d) {
        List<SourceRow> out = new ArrayList<>(rows.size());
        for (SourceRow r : rows) {
            if (d.firstTime(r.source() + "|" + r.key() + "|" + r.season() + "|" + r.clubKey())) {
                out.add(r);
            }
        }
        return out;
    }

    private static String stintKey(String playerId, League l, int season, String clubKey) {
        return playerId + "|" + l.name() + "|" + season + "|" + clubKey;
    }

    private static Player toPlayer(TransfermarktReader.TmPlayer t, String id) {
        if (t == null) {
            return new Player(id, id, List.of(), null, 0, "", null, "", "", 0, 0);
        }
        return new Player("tm:" + id, t.name(), List.of(), t.birthDate(), 0, t.nation(),
                Position.fromTransfermarkt(t.subPosition(), t.position()), t.subPosition(), t.foot(), t.heightCm(),
                t.marketValueEur());
    }

    static Position fbrefPosition(String pos) {
        if (pos == null || pos.isBlank()) {
            return null;
        }
        return switch (pos.split(",")[0].trim()) {
            case "GK" -> Position.GK;
            case "DF" -> Position.CB;
            case "MF" -> Position.CM;
            case "FW" -> Position.ST;
            default -> null;
        };
    }

    private static double zero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    /** A player's season at one club being assembled from several sources. */
    private static final class Stint {
        final String playerId;
        final League league;
        final int season;
        final String clubKey;
        final String club;
        final double[] stats = PlayerSeason.emptyStats();
        final Set<Source> sources = EnumSet.noneOf(Source.class);
        Position position;

        Stint(String playerId, SourceRow first) {
            this.playerId = playerId;
            this.league = first.league();
            this.season = first.season();
            this.clubKey = first.clubKey();
            this.club = first.club();
        }

        /** Advanced sources override Transfermarkt's counts so per-90 rates use one consistent minutes figure. */
        void merge(SourceRow r) {
            boolean advanced = r.source() != Source.TRANSFERMARKT;
            for (int i = 0; i < Stat.COUNT; i++) {
                double v = r.stats()[i];
                if (!Double.isNaN(v) && (advanced || Double.isNaN(stats[i]))) {
                    stats[i] = v;
                }
            }
            sources.add(r.source());
            if (r.source() == Source.UNDERSTAT) {
                Position p = Position.fromUnderstat(r.positionCode());
                if (p != null) {
                    position = p;
                }
            }
        }
    }
}

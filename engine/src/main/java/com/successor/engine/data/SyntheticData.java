package com.successor.engine.data;

import com.successor.engine.model.League;
import com.successor.engine.model.Player;
import com.successor.engine.model.PlayerSeason;
import com.successor.engine.model.Position;
import com.successor.engine.model.Source;
import com.successor.engine.model.Stat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Realistic synthetic player-seasons for tests, benchmarks (any size, same seed → same data) and the sample that
 * ships with the repository. Per-90 rates start from typical values for each position, then vary with the player's
 * ability, a playing style (more attacking, creative or defensive), an aging curve peaking around 27, and season-to-
 * season noise. Minutes rise with ability and fall with age. Every stat is present, including the defensive ones the
 * real data has for only one season. Names are made up.
 */
public final class SyntheticData {
    private SyntheticData() {
    }

    /** Writes the committed sample: {@code java -cp engine/target/classes …SyntheticData data/sample}. */
    public static void main(String[] args) {
        java.nio.file.Path out = java.nio.file.Path.of(args.length > 0 ? args[0] : "data/sample");
        Dataset d = generate(1500, 2014, 12, 42);
        DatasetIO.write(d, out);
        com.successor.engine.ScoutingEngine.writeCalibration(d, out);
        System.out.println("wrote " + d.players().size() + " synthetic players, " + d.seasons().size()
                + " player-seasons (and calibration) to " + out);
    }

    /** Typical per-90 values by position, in {@link Position} order: GK CB FB DM CM AM W ST. */
    private static final double[][] BASE = new double[Stat.COUNT][];

    private static void base(Stat s, double... perPosition) {
        BASE[s.ordinal()] = perPosition;
    }

    static {
        base(Stat.GOALS, 0, .05, .05, .06, .10, .25, .30, .45);
        base(Stat.XG, 0, .06, .05, .06, .10, .22, .28, .42);
        base(Stat.ASSISTS, 0, .03, .12, .07, .12, .22, .20, .14);
        base(Stat.XA, 0, .03, .11, .07, .12, .22, .20, .13);
        base(Stat.SHOTS, 0, .5, .6, .8, 1.2, 2.2, 2.4, 3.0);
        base(Stat.SHOTS_ON_TARGET, 0, .15, .2, .25, .4, .8, .9, 1.2);
        base(Stat.KEY_PASSES, .02, .3, 1.0, .8, 1.2, 2.2, 1.9, 1.2);
        base(Stat.XG_CHAIN, .05, .2, .35, .35, .45, .6, .6, .6);
        base(Stat.XG_BUILDUP, .05, .2, .25, .3, .3, .25, .2, .12);
        base(Stat.SCA, .3, 1.0, 2.4, 2.0, 2.8, 4.5, 4.2, 3.0);
        base(Stat.GCA, 0, .1, .25, .2, .3, .55, .5, .45);
        base(Stat.PROG_PASSES, .5, 3.5, 4.5, 6.0, 5.5, 4.5, 3.0, 1.5);
        base(Stat.PROG_CARRIES, .1, 1.0, 2.5, 1.5, 2.2, 3.5, 4.5, 2.0);
        base(Stat.PROG_RECEPTIONS, 0, .5, 4.0, 2.0, 4.5, 8.0, 10.0, 8.5);
        base(Stat.PASSES_FINAL_THIRD, .5, 3.5, 3.5, 5.5, 5.0, 3.5, 2.0, 1.0);
        base(Stat.PASSES_INTO_BOX, 0, .2, 1.0, .7, 1.0, 2.0, 1.6, .8);
        base(Stat.CROSSES_INTO_BOX, 0, .05, .4, .1, .2, .3, .5, .1);
        base(Stat.CARRIES_INTO_BOX, 0, .1, .5, .2, .4, 1.0, 1.3, .8);
        base(Stat.PASSES_COMPLETED, 25, 55, 40, 55, 50, 38, 25, 17);
        base(Stat.PASSES_ATTEMPTED, 33, 62, 52, 63, 58, 48, 34, 26);
        base(Stat.TOUCHES, 38, 72, 68, 75, 70, 62, 52, 40);
        base(Stat.TOUCHES_ATT_BOX, 0, .6, 1.5, .9, 1.8, 4.0, 5.0, 6.5);
        base(Stat.TAKE_ONS, 0, .3, .8, .6, 1.0, 1.9, 2.6, 1.2);
        base(Stat.CROSSES, 0, .1, 2.5, .5, 1.0, 1.8, 3.0, .5);
        base(Stat.FOULS_DRAWN, .2, .6, 1.0, 1.2, 1.3, 1.8, 1.9, 1.6);
        base(Stat.TACKLES, .05, 1.6, 2.0, 2.6, 2.0, 1.3, 1.1, .6);
        base(Stat.TACKLES_WON, .03, 1.0, 1.2, 1.6, 1.2, .8, .7, .4);
        base(Stat.INTERCEPTIONS, .05, 1.4, 1.1, 1.5, 1.0, .5, .4, .2);
        base(Stat.BLOCKS, .05, 1.4, 1.0, 1.2, 1.0, .6, .5, .4);
        base(Stat.CLEARANCES, .8, 4.5, 2.0, 1.6, 1.0, .4, .3, .6);
        base(Stat.RECOVERIES, 4.0, 4.5, 5.0, 6.5, 5.5, 4.0, 3.5, 2.5);
        base(Stat.AERIALS_WON, .3, 2.6, 1.0, 1.4, .9, .5, .4, 2.0);
        base(Stat.DISPOSSESSED, 0, .3, .8, .9, 1.0, 1.4, 1.6, 1.4);
        base(Stat.MISCONTROLS, .05, .5, 1.0, 1.0, 1.2, 1.8, 2.2, 2.4);
        base(Stat.YELLOW_CARDS, .03, .2, .18, .25, .2, .15, .12, .12);
        base(Stat.RED_CARDS, .002, .01, .008, .01, .006, .004, .004, .004);
        base(Stat.GOALS_AGAINST, 1.3, 0, 0, 0, 0, 0, 0, 0);
        base(Stat.SHOTS_ON_TARGET_AGAINST, 4.0, 0, 0, 0, 0, 0, 0, 0);
        base(Stat.SAVES, 2.8, 0, 0, 0, 0, 0, 0, 0);
        base(Stat.PSXG, 1.3, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Which style axis a stat belongs to: 0 = attacking, 1 = creative, 2 = defensive, −1 = neutral. */
    private static int axis(Stat s) {
        return switch (s) {
            case GOALS, NP_GOALS, XG, NPXG, SHOTS, SHOTS_ON_TARGET, TOUCHES_ATT_BOX, CARRIES_INTO_BOX, TAKE_ONS,
                 PROG_RECEPTIONS -> 0;
            case ASSISTS, XA, KEY_PASSES, SCA, GCA, PASSES_INTO_BOX, CROSSES_INTO_BOX, CROSSES, XG_CHAIN,
                 PROG_PASSES, PASSES_FINAL_THIRD, XG_BUILDUP -> 1;
            case TACKLES, TACKLES_WON, INTERCEPTIONS, BLOCKS, CLEARANCES, RECOVERIES, AERIALS_WON -> 2;
            default -> -1;
        };
    }

    private static final Position[] POSITIONS = Position.values();
    private static final double[] POSITION_SHARE = {.08, .18, .16, .09, .15, .09, .12, .13};
    private static final String[] FIRST = {"Alen", "Bruno", "Cael", "Dario", "Elian", "Fabio", "Gael", "Hugo", "Ilan",
            "Joel", "Kian", "Luca", "Mateo", "Nilo", "Oren", "Pavel", "Rafa", "Sami", "Teo", "Ugo", "Vito", "Yann",
            "Zeno", "Aris", "Bram", "Cyro", "Davi", "Emre", "Finn", "Goran"};
    private static final String[] SYLL = {"ra", "mo", "ki", "len", "do", "var", "si", "tor", "an", "bel", "cou", "dri",
            "fe", "gal", "hu", "ja", "lo", "mez", "ni", "por", "qui", "ros", "sen", "tal", "vi", "zan"};

    /**
     * @param players     number of players
     * @param firstSeason first season start year
     * @param seasons     seasons per league (careers are 1–10 seasons inside this window)
     */
    public static Dataset generate(int players, int firstSeason, int seasons, long seed) {
        if (players < 0 || seasons < 1) {
            throw new IllegalArgumentException("players ≥ 0 and seasons ≥ 1 required");
        }
        SplittableRandom rnd = new SplittableRandom(seed);
        League[] leagues = League.bigFive().toArray(new League[0]);
        List<Player> ps = new ArrayList<>(players);
        List<PlayerSeason> rows = new ArrayList<>();
        int lastSeason = firstSeason + seasons - 1;
        for (int i = 0; i < players; i++) {
            Position pos = pick(rnd);
            double ability = gaussian(rnd);
            double[] style = {rnd.nextDouble(0.6, 1.4), rnd.nextDouble(0.6, 1.4), rnd.nextDouble(0.6, 1.4)};
            int debutAge = 17 + rnd.nextInt(6);
            int length = 1 + rnd.nextInt(Math.min(10, seasons));
            int start = firstSeason + rnd.nextInt(Math.max(1, seasons - length + 1));
            int birthYear = start - debutAge;
            LocalDate birth = LocalDate.of(birthYear, 1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
            String id = "syn:" + i;
            String name = FIRST[rnd.nextInt(FIRST.length)] + " " + capitalise(SYLL[rnd.nextInt(SYLL.length)]
                    + SYLL[rnd.nextInt(SYLL.length)] + (rnd.nextBoolean() ? SYLL[rnd.nextInt(SYLL.length)] : ""));
            ps.add(new Player(id, name, List.of(), birth, birthYear, "Synthetic", pos, pos.label(),
                    rnd.nextBoolean() ? "right" : "left", 170 + rnd.nextInt(25),
                    Math.round(Math.exp(15 + ability) / 100_000) * 100_000));
            League league = leagues[rnd.nextInt(leagues.length)];
            int club = rnd.nextInt(18);
            double form = 0;
            for (int season = start; season < start + length && season <= lastSeason; season++) {
                if (rnd.nextDouble() < 0.12) { // transfer
                    league = leagues[rnd.nextInt(leagues.length)];
                    club = rnd.nextInt(18);
                }
                int age = season - birthYear;
                double aging = -0.004 * (age - 27) * (age - 27);
                form = 0.5 * form + 0.15 * gaussian(rnd);
                double level = Math.exp(0.22 * ability + aging + form);
                double minutes = Math.max(0, Math.min(3420, 1700 + 900 * ability + 2000 * aging + 600 * gaussian(rnd)));
                if (minutes < 90) {
                    continue;
                }
                double[] s = PlayerSeason.emptyStats();
                double nineties = minutes / 90.0;
                s[Stat.MINUTES.ordinal()] = Math.round(minutes);
                s[Stat.GAMES.ordinal()] = Math.max(1, Math.round(nineties * 1.15));
                s[Stat.STARTS.ordinal()] = Math.round(Math.min(s[Stat.GAMES.ordinal()], nineties * 0.95));
                for (Stat st : Stat.values()) {
                    double[] b = BASE[st.ordinal()];
                    if (b == null) {
                        continue;
                    }
                    int ax = axis(st);
                    double rate = b[pos.ordinal()] * level * (ax >= 0 ? style[ax] : 1)
                            * Math.exp(0.15 * gaussian(rnd));
                    s[st.ordinal()] = round1(rate * nineties);
                }
                s[Stat.NP_GOALS.ordinal()] = Math.max(0, s[Stat.GOALS.ordinal()] - (rnd.nextDouble() < 0.2 ? 1 : 0));
                s[Stat.NPXG.ordinal()] = round1(s[Stat.XG.ordinal()] * 0.92);
                s[Stat.CLEAN_SHEETS.ordinal()] = pos == Position.GK ? Math.round(nineties * 0.3) : Double.NaN;
                if (pos != Position.GK) {
                    for (Stat g : new Stat[]{Stat.GOALS_AGAINST, Stat.SHOTS_ON_TARGET_AGAINST, Stat.SAVES, Stat.PSXG}) {
                        s[g.ordinal()] = Double.NaN;
                    }
                }
                rows.add(new PlayerSeason(id, season, league, league.label() + " Club " + (club + 1), pos,
                        EnumSet.of(Source.SYNTHETIC), s));
            }
        }
        List<PlayerSeason> kept = rows;
        java.util.Set<String> withSeasons = new java.util.HashSet<>();
        kept.forEach(r -> withSeasons.add(r.playerId()));
        return new Dataset(ps.stream().filter(p -> withSeasons.contains(p.id())).toList(), kept);
    }

    /** Players with made-up names only (no seasons), for search benchmarks of any size. */
    public static List<Player> names(int n, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        List<Player> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = FIRST[rnd.nextInt(FIRST.length)] + " " + capitalise(SYLL[rnd.nextInt(SYLL.length)]
                    + SYLL[rnd.nextInt(SYLL.length)] + SYLL[rnd.nextInt(SYLL.length)]) + (rnd.nextInt(4) == 0
                    ? " " + capitalise(SYLL[rnd.nextInt(SYLL.length)] + SYLL[rnd.nextInt(SYLL.length)]) : "");
            out.add(new Player("n:" + i, name, List.of(), null, 0, "", Position.CM, "", "", 0, 0));
        }
        return out;
    }

    private static Position pick(SplittableRandom rnd) {
        double u = rnd.nextDouble();
        for (int i = 0; i < POSITIONS.length; i++) {
            u -= POSITION_SHARE[i];
            if (u < 0) {
                return POSITIONS[i];
            }
        }
        return Position.ST;
    }

    static double gaussian(SplittableRandom rnd) {
        // Box–Muller
        double u = 1 - rnd.nextDouble();
        double v = rnd.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

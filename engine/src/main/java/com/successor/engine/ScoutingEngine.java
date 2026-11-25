package com.successor.engine;

import com.successor.engine.data.Dataset;
import com.successor.engine.data.DatasetIO;
import com.successor.engine.data.SyntheticData;
import com.successor.engine.scouting.Cohorts;
import com.successor.engine.scouting.Comparer;
import com.successor.engine.scouting.LeagueStrength;
import com.successor.engine.scouting.PlayerSearch;
import com.successor.engine.scouting.Projector;
import com.successor.engine.scouting.Scout;
import com.successor.engine.scouting.SquadPlanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point to the scouting engine. Holds the loaded dataset and exposes the operations the API offers
 * (search, profiles, similarity, successor search, squad planning, projections). Thread-safe for reads once loaded.
 */
public final class ScoutingEngine {
    private volatile Dataset data = new Dataset(List.of(), List.of());
    private volatile String origin = "none";
    private volatile PlayerSearch search = new PlayerSearch(data);
    private volatile Scout scout = new Scout(data, Scout.DEFAULT_MIN_MINUTES);
    private volatile Projector.Backtest backtest;

    /** Seasons the projection is fitted on and tested on (targets: the season being projected). */
    public static final List<Integer> TRAIN = List.of(2017, 2018, 2019);
    public static final List<Integer> TEST = List.of(2020, 2021, 2022, 2023);

    /**
     * Loads the first dataset found: {@code preferred} if given, else {@code data/processed} (built from the real
     * sources), else {@code data/sample} (synthetic, committed), else a synthetic dataset generated in memory.
     */
    public static ScoutingEngine load(Path preferred) {
        ScoutingEngine e = new ScoutingEngine();
        if (preferred != null) {
            e.use(DatasetIO.read(preferred), preferred.toString());
            e.loadCalibration(preferred);
            return e;
        }
        for (Path p : List.of(Path.of("data/processed"), Path.of("data/sample"))) {
            if (Files.isRegularFile(p.resolve(DatasetIO.PLAYERS))) {
                e.use(DatasetIO.read(p), p.toString());
                e.loadCalibration(p);
                return e;
            }
        }
        e.use(SyntheticData.generate(1500, 2014, 12, 42), "synthetic (generated)");
        return e;
    }

    public void use(Dataset d, String origin) {
        PlayerSearch s = new PlayerSearch(d);
        Scout sc = new Scout(d, Scout.DEFAULT_MIN_MINUTES);
        this.data = d;
        this.search = s;
        this.scout = sc;
        this.origin = origin;
    }

    public PlayerSearch search() {
        return search;
    }

    public Scout scout() {
        return scout;
    }

    public SquadPlanner squadPlanner() {
        return new SquadPlanner(data, scout);
    }

    public Comparer comparer() {
        return new Comparer(data, scout);
    }

    /** The projection backtest (run once on first request, a few seconds on the real data, then cached). */
    public synchronized Projector.Backtest backtest() {
        if (backtest == null) {
            backtest = runBacktest(data);
        }
        return backtest;
    }

    /**
     * The backtest on its own cohorts, with league-strength factors estimated only from transfers whose Big-5 season
     * is a training season (so no test season informs them), then the league check: the same test seasons without
     * the factors, at the calibration the backtest chose.
     */
    static Projector.Backtest runBacktest(Dataset d) {
        int min = Scout.DEFAULT_MIN_MINUTES;
        int lastTrain = TRAIN.stream().mapToInt(Integer::intValue).max().orElseThrow();
        Cohorts adjusted = new Cohorts(d, min);
        adjusted.setStrength(LeagueStrength.estimate(d, min, lastTrain));
        Projector withFactors = new Projector(d, adjusted);
        Projector.Backtest b = withFactors.backtest(TRAIN, TEST);
        Projector without = new Projector(d, new Cohorts(d, min));
        without.calibrate(b.regressionMinutes(), b.rangeScale(), b.chosenMethod());
        Projector.Method m = b.chosenMethod();
        double[] arr = withFactors.errors(TEST, m, true);
        double[] arr0 = without.errors(TEST, m, true);
        double[] all = withFactors.errors(TEST, m, false);
        double[] all0 = without.errors(TEST, m, false);
        return b.withLeagueCheck(new Projector.LeagueCheck(m.name(),
                "transfers into the Big 5 up to " + com.successor.engine.model.PlayerSeason.label(lastTrain),
                (int) arr[2], round3(arr[0] / arr[1]), round3(arr0[0] / arr0[1]), round3(all[0] / all[1]),
                round3(all0[0] / all0[1]),
                "Arrivals: test-season Big-5 players whose previous season was in another league. Their projections "
                        + "depend most on how that league's numbers are converted."));
    }

    private static double round3(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 1000) / 1000.0;
    }

    /**
     * Reads {@code calibration.properties} and {@code league_strength.properties} (written by the dataset build) if
     * the folder has them; without the latter the factors are estimated from the data now.
     */
    void loadCalibration(Path dir) {
        Path ls = dir.resolve(LEAGUE_STRENGTH);
        if (Files.isRegularFile(ls)) {
            java.util.Properties p = new java.util.Properties();
            try (var in = Files.newBufferedReader(ls)) {
                p.load(in);
                scout.cohorts().setStrength(LeagueStrength.fromProperties(p));
            } catch (java.io.IOException | RuntimeException ex) {
                throw new IllegalArgumentException("invalid " + ls + ": " + ex.getMessage(), ex);
            }
        } else {
            scout.cohorts().setStrength(LeagueStrength.estimateAll(data, Scout.DEFAULT_MIN_MINUTES));
        }
        Path f = dir.resolve(CALIBRATION);
        if (!Files.isRegularFile(f)) {
            return;
        }
        java.util.Properties p = new java.util.Properties();
        try (var in = Files.newBufferedReader(f)) {
            p.load(in);
            scout.projector().calibrate(Double.parseDouble(p.getProperty("regressionMinutes")),
                    Double.parseDouble(p.getProperty("rangeScale")),
                    Projector.Method.valueOf(p.getProperty("method")));
        } catch (java.io.IOException | RuntimeException ex) {
            throw new IllegalArgumentException("invalid " + f + ": " + ex.getMessage(), ex);
        }
    }

    public static final String CALIBRATION = "calibration.properties";
    public static final String LEAGUE_STRENGTH = "league_strength.properties";

    /**
     * Runs the backtest on a dataset and writes the fitted values next to it, with the league-strength factors the
     * app uses (estimated from every transfer in the data).
     */
    public static Projector.Backtest writeCalibration(Dataset d, Path dir) {
        Projector.Backtest b = runBacktest(d);
        String text = "# fitted on target seasons " + TRAIN + " by the projection backtest\n"
                + "regressionMinutes=" + b.regressionMinutes() + "\nrangeScale=" + b.rangeScale() + "\nmethod="
                + b.chosenMethod() + "\n";
        try {
            Files.writeString(dir.resolve(CALIBRATION), text);
            Files.writeString(dir.resolve(LEAGUE_STRENGTH),
                    LeagueStrength.estimateAll(d, Scout.DEFAULT_MIN_MINUTES).toProperties());
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return b;
    }

    public Dataset data() {
        return data;
    }

    public DatasetSummary summary() {
        return data.summary().withOrigin(origin);
    }
}

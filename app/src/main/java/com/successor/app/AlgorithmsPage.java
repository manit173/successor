package com.successor.app;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Under the hood: the projection backtest and the benchmark suites, all run by the Java backend
 * ({@code GET /api/backtest}, {@code POST /api/benchmarks/{id}}).
 */
final class AlgorithmsPage {
    private final Ui.Services app;
    private final VBox root = new VBox(18);
    private final VBox backtest = new VBox(10);
    private final VBox suites = new VBox(14);

    AlgorithmsPage(Ui.Services app) {
        this.app = app;
        root.getChildren().addAll(new VBox(6, Ui.title("Algorithms & benchmarks", Styles.TITLE_2), Ui.muted("Every "
                + "number here is computed by the Java backend on request: the projection backtest on past seasons, "
                + "and each algorithm raced against its baseline on generated inputs of growing size.")),
                Ui.title("Projection accuracy", Styles.TITLE_3), backtest, Ui.title("Benchmarks", Styles.TITLE_3), suites);
        root.setPadding(new Insets(28, 32, 28, 32));
        backtest.getChildren().add(Ui.muted("Loading the backtest (projects four past seasons from earlier data)…"));
        app.call(() -> app.backend().get("/api/backtest"), this::showBacktest);
        app.call(() -> app.backend().get("/api/benchmarks"), this::showSuites);
    }

    Node node() {
        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        sp.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        return sp;
    }

    private void showBacktest(JsonNode b) {
        GridPane g = new GridPane();
        g.setHgap(22);
        g.setVgap(6);
        String[] head = {"Method", "Error (z units)", "vs “next = last”", "80% range holds"};
        for (int c = 0; c < head.length; c++) {
            g.add(Ui.small(head[c]), c, 0);
        }
        int row = 1;
        for (JsonNode m : b.path("overall")) {
            String method = m.path("method").asString();
            Label name = new Label(switch (method) {
                case "NAIVE" -> "Naive: next season = last season (baseline)";
                case "MEAN3" -> "Mean of the last three seasons (baseline)";
                case "MARCEL" -> "Marcel: weighted 5/4/3, regressed, aging curve";
                case "COMPARABLES" -> "Comparables: 25 most similar players at that age";
                default -> "Blend of Marcel and comparables";
            });
            if (method.equals(b.path("chosenMethod").asString())) {
                name.getStyleClass().add(Styles.TEXT_BOLD);
                name.setText(name.getText() + "  ← used by the app");
            }
            double cov = Ui.num(m.path("coverage80"));
            g.addRow(row++, name, new Label(String.format(Locale.ROOT, "%.3f", Ui.num(m.path("mae")))),
                    new Label(String.format(Locale.ROOT, "%+.1f%%", Ui.num(m.path("improvementVsNaive")))),
                    new Label(Double.isNaN(cov) ? "–" : String.format(Locale.ROOT, "%.1f%%", cov)));
        }
        StringBuilder seasons = new StringBuilder();
        b.path("testSeasons").forEach(s -> seasons.append(seasons.isEmpty() ? "" : ", ").append(s.asInt()).append('/')
                .append(String.format("%02d", (s.asInt() + 1) % 100)));
        Label note = Ui.small(String.format(Locale.ROOT, "Each test season (%s) is projected only from the seasons "
                        + "before it. R = %.0f minutes, the range width and the method were chosen on target seasons %s "
                        + "before testing. %s", seasons, Ui.num(b.path("regressionMinutes")),
                b.path("trainSeasons").toString(), b.path("note").asString()));
        backtest.getChildren().setAll(Ui.card(g, note));
        JsonNode lc = b.path("leagueCheck");
        if (!lc.isMissingNode() && !lc.isNull()) {
            GridPane t = new GridPane();
            t.setHgap(22);
            t.setVgap(6);
            t.addRow(0, Ui.small("Players projected"), Ui.small("With league factors"), Ui.small("Without"));
            t.addRow(1, new Label(String.format(Locale.ROOT, "%d arrivals from other leagues", lc.path("arrivals")
                            .asInt())), new Label(String.format(Locale.ROOT, "%.3f", Ui.num(lc.path("arrivalsMae")))),
                    new Label(String.format(Locale.ROOT, "%.3f", Ui.num(lc.path("arrivalsMaeUnadjusted")))));
            t.addRow(2, new Label("All test players"), new Label(String.format(Locale.ROOT, "%.3f",
                    Ui.num(lc.path("overallMae")))), new Label(String.format(Locale.ROOT, "%.3f",
                    Ui.num(lc.path("overallMaeUnadjusted")))));
            Label ln = Ui.small("League strength: per-90 numbers from leagues outside the Big 5 are converted by "
                    + "one factor per league and stat, measured from players who moved into the Big 5 (relative to "
                    + "moves between Big-5 clubs) and shrunk toward the typical league where the sample is small. "
                    + "Here the factors come only from " + lc.path("factorsFrom").asString() + ", before every test "
                    + "season. " + lc.path("note").asString());
            backtest.getChildren().addAll(Ui.title("League strength", Styles.TITLE_4), Ui.card(t, ln));
        }
    }

    private void showSuites(JsonNode listing) {
        suites.getChildren().clear();
        for (JsonNode s : listing.path("suites")) {
            VBox body = new VBox(8);
            Button run = new Button("Run (quick sizes)");
            run.getStyleClass().add(Styles.SMALL);
            String id = s.path("id").asString();
            run.setOnAction(e -> {
                run.setDisable(true);
                body.getChildren().setAll(Ui.muted("Running " + id + "… (a few seconds)"));
                app.call(() -> {
                    try {
                        return app.backend().post("/api/benchmarks/" + id + "?quick=true", java.util.Map.of());
                    } finally {
                        javafx.application.Platform.runLater(() -> run.setDisable(false));
                    }
                }, r -> body.getChildren().setAll(table(r)));
            });
            JsonNode last = listing.path("latest").path(id);
            if (!last.isMissingNode()) {
                body.getChildren().setAll(table(last));
            }
            HBox head = new HBox(10, Ui.title(s.path("title").asString(), Styles.TEXT_BOLD), Ui.grow(), run);
            head.setAlignment(Pos.CENTER_LEFT);
            suites.getChildren().add(Ui.card(head, Ui.small(s.path("algorithm").asString() + ". "
                    + s.path("description").asString()), body));
        }
        suites.getChildren().add(Ui.small(listing.path("environment").asString() + ". Full sizes: java -cp "
                + "engine/target/classes com.successor.engine.bench.BenchmarkCli docs/benchmarks full"));
    }

    private static Node table(JsonNode suite) {
        GridPane g = new GridPane();
        g.setHgap(20);
        g.setVgap(6);
        g.add(Ui.small(suite.path("sizeLabel").asString()), 0, 0);
        JsonNode first = suite.path("rows").get(0).path("cells");
        for (int c = 0; c < first.size(); c++) {
            Label h = Ui.small(first.get(c).path("variant").asString() + (first.get(c).path("baseline").asBoolean()
                    ? " (baseline)" : ""));
            g.add(h, c + 1, 0);
        }
        g.add(Ui.small("answers"), first.size() + 1, 0);
        int row = 1;
        for (JsonNode r : suite.path("rows")) {
            g.add(new Label(r.path("size").asString()), 0, row);
            double best = Double.POSITIVE_INFINITY;
            for (JsonNode c : r.path("cells")) {
                best = Math.min(best, Ui.num(c.path("ms")));
            }
            int col = 1;
            for (JsonNode c : r.path("cells")) {
                double ms = Ui.num(c.path("ms"));
                Label time = new Label(ms < 10 ? String.format(Locale.ROOT, "%.3f ms", ms)
                        : String.format(Locale.ROOT, "%,.0f ms", ms));
                if (ms == best) {
                    time.getStyleClass().add(Styles.SUCCESS);
                }
                String ans = c.path("answer").asString();
                Label work = Ui.small(String.format(Locale.ROOT, "%,d %s", c.path("work").asLong(),
                        suite.path("workUnit").asString()) + (ans.length() < 34 && !ans.contains("#")
                        ? " · " + ans : ""));
                g.add(new VBox(1, time, work), col++, row);
            }
            JsonNode agree = r.path("answersAgree");
            g.add(Ui.small(agree.isNull() || agree.isMissingNode() ? "optimal vs greedy"
                    : agree.asBoolean() ? "agree ✓" : "DIFFER"), col, row);
            row++;
        }
        return g;
    }
}

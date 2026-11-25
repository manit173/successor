package com.successor.app;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Two or three players side by side on the stats that matter for the first player's role ({@code POST /api/compare}). */
final class ComparePage {
    private final Ui.Services app;
    private final VBox root = new VBox(18);
    private final VBox result = new VBox(12);
    private final List<PlayerPicker> pickers = new ArrayList<>();

    ComparePage(Ui.Services app) {
        this.app = app;
        for (int i = 0; i < 3; i++) {
            pickers.add(new PlayerPicker(app, i == 2 ? "Third player (optional)" : "Player " + (i + 1), (id, n) -> {
            }));
        }
        Button go = new Button("Compare");
        go.getStyleClass().add(Styles.ACCENT);
        go.setDefaultButton(true);
        go.setOnAction(e -> run());
        go.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox form = new HBox(10, pickers.get(0), pickers.get(1), pickers.get(2), go);
        form.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(new VBox(6, Ui.title("Compare", Styles.TITLE_2), Ui.muted("Players are compared on "
                + "the stats of the first player's position and role, each against that position's cohort in the "
                + "same season (last two seasons with 900+ minutes).")), Ui.card(form), result);
        root.setPadding(new Insets(28, 32, 28, 32));
        result.getChildren().add(Ui.muted("Pick two or three players, e.g. Kevin De Bruyne and Rayan Cherki."));
    }

    Node node() {
        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        sp.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        return sp;
    }

    void preset(String id, String name) {
        pickers.getFirst().set(id, name);
    }

    /** Fills the pickers in order and compares (used by --compare=id,id for screenshots). */
    void presetAll(java.util.List<String> ids) {
        for (int i = 0; i < Math.min(3, ids.size()); i++) {
            String id = ids.get(i);
            PlayerPicker picker = pickers.get(i);
            picker.set(id, id);
            app.call(() -> app.backend().get("/api/players/" + java.net.URLEncoder.encode(id,
                    java.nio.charset.StandardCharsets.UTF_8)), p -> picker.set(id, p.path("name").asString(id)),
                    err -> {
                    });
        }
        run();
    }

    private void run() {
        List<String> ids = pickers.stream().map(PlayerPicker::pickedId).filter(x -> x != null).distinct().toList();
        if (ids.size() < 2) {
            result.getChildren().setAll(Ui.muted("Pick at least two different players from the suggestions."));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ids", ids);
        result.getChildren().setAll(Ui.muted("Comparing…"));
        app.call(() -> app.backend().post("/api/compare", body), this::show,
                err -> result.getChildren().setAll(Ui.muted("Could not compare: " + err)));
    }

    private void show(JsonNode r) {
        JsonNode players = r.path("players");
        GridPane g = new GridPane();
        g.setHgap(18);
        g.setVgap(8);
        g.add(Ui.small("Compared as " + r.path("position").asString() + " · " + r.path("role").asString()), 0, 0);
        int c = 1;
        for (JsonNode p : players) {
            Label name = Ui.title(p.path("name").asString(), Styles.TEXT_BOLD);
            Label meta = Ui.small(p.path("position").asString("") + " · " + p.path("club").asString() + " · age "
                    + p.path("age").asInt() + " · " + String.join(" + ", list(p.path("seasons"))));
            VBox head = new VBox(2, name, meta);
            for (String k : new String[]{"positionNote", "leagueNote"}) {
                if (!p.path(k).isNull() && !p.path(k).asString("").isBlank()) {
                    head.getChildren().add(Ui.note(p.path(k).asString()));
                }
            }
            g.add(head, c++, 0);
        }
        int row = 1;
        for (JsonNode st : r.path("stats")) {
            String key = st.path("stat").asString();
            Label label = new Label(st.path("label").asString());
            if (st.path("inRole").asBoolean()) {
                label.getStyleClass().add(Styles.TEXT_BOLD);
            }
            g.add(label, 0, row);
            c = 1;
            for (JsonNode p : players) {
                JsonNode v = p.path("stats").path(key);
                if (v.isMissingNode()) {
                    g.add(Ui.small("not measured"), c++, row);
                    continue;
                }
                double pct = Ui.num(v.path("percentile"));
                double per90 = Ui.num(v.path("per90"));
                ProgressBar bar = new ProgressBar(Double.isNaN(pct) ? 0 : pct / 100);
                bar.setPrefWidth(150);
                String text = (Double.isNaN(per90) ? "–" : String.format(Locale.ROOT, "%.2f", per90))
                        + (Double.isNaN(pct) ? "" : " · " + Ui.ordinal(pct)) + "  (" + v.path("season").asString()
                        + ")";
                HBox cell = new HBox(8, bar, Ui.small(text));
                cell.setAlignment(Pos.CENTER_LEFT);
                g.add(cell, c++, row);
            }
            row++;
        }
        List<String> sims = new ArrayList<>();
        JsonNode sim = r.path("similarity");
        for (int a = 0; a < players.size(); a++) {
            for (int b = a + 1; b < players.size(); b++) {
                double s = Ui.num(sim.get(a).get(b));
                sims.add(players.get(a).path("name").asString() + " ↔ " + players.get(b).path("name").asString() + ": "
                        + (Double.isNaN(s) ? "too few stats in common" : String.format(Locale.ROOT, "%.0f%% similar", s)));
            }
        }
        // overlay radar: every player's percentile on each stat
        List<String> axes = new ArrayList<>();
        List<double[]> vals = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            vals.add(new double[r.path("stats").size()]);
        }
        int k = 0;
        for (JsonNode st : r.path("stats")) {
            axes.add(Charts.shortLabel(st.path("stat").asString(), st.path("label").asString()));
            for (int i = 0; i < players.size(); i++) {
                vals.get(i)[k] = Ui.num(players.get(i).path("stats").path(st.path("stat").asString()).path("percentile"));
            }
            k++;
        }
        List<Charts.Series> series = new ArrayList<>();
        HBox legend = new HBox(16);
        legend.setAlignment(Pos.CENTER);
        Charts.Palette pal = new Charts.Palette();
        for (int i = 0; i < players.size(); i++) {
            series.add(new Charts.Series(players.get(i).path("name").asString(), vals.get(i)));
            javafx.scene.layout.Region dot = new javafx.scene.layout.Region();
            dot.getStyleClass().add("sc-legend-dot");
            dot.setStyle("-fx-background-color: " + Ui.css(pal.series(i)) + ";");
            HBox item = new HBox(6, dot, new Label(players.get(i).path("name").asString()));
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        Node radar = new Charts.Radar(axes, series, 480);
        VBox radarCard = Ui.card(Ui.title("Percentile profile", Styles.TITLE_4), radar, legend);
        radarCard.setAlignment(Pos.TOP_CENTER);
        result.getChildren().setAll(radarCard, Ui.card(g), Ui.muted(String.join("    ", sims)), Ui.small("Bars: percentile among "
                + "that position's players with 900+ minutes, in the latest of the player's two compared seasons that "
                + "measured the stat (shown in brackets); numbers are per 90. Bold stats define the role."));
    }

    private static List<String> list(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asString()));
        return out;
    }
}

package com.successor.app;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Find a successor: choose a player and filters, then see who projects closest to his peak, with the reasons.
 * Everything is computed by the API ({@code POST /api/successor}); this page only collects input and draws the answer.
 */
final class SuccessorPage {
    private static final String[] BIG5 = {"Premier League", "La Liga", "Bundesliga", "Serie A", "Ligue 1"};

    private final Ui.Services app;
    private final VBox root = new VBox(18);
    private final VBox results = new VBox(12);
    private final TextField target = new TextField();
    private final ContextMenu picks = new ContextMenu();
    private final ComboBox<String> peakFrom = new ComboBox<>();
    private final ComboBox<String> peakTo = new ComboBox<>();
    private final CheckBox ageLimit = new CheckBox("Max age next season");
    private final Spinner<Integer> maxAge = new Spinner<>(16, 40, 24);
    private final Spinner<Integer> minMinutes = new Spinner<>(0, 3000, 900, 100);
    private final Spinner<Integer> k = new Spinner<>(1, 50, 10);
    private final List<ToggleButton> leagues = new ArrayList<>();
    private final Button run = new Button("Find successors");
    private final ComboBox<String> mode = new ComboBox<>();
    private final CheckBox samePosition = new CheckBox("Same position only");
    private String targetId;
    private final Map<String, Integer> seasonYear = new LinkedHashMap<>();

    SuccessorPage(Ui.Services app) {
        this.app = app;
        Label h = Ui.title("Find a successor", Styles.TITLE_2);
        Label sub = Ui.muted("Choose a player. Successor finds his peak seasons, builds a role profile of them, and "
                + "ranks every active player in the leagues you select by how close his projected next season (or, "
                + "if you choose, his recent seasons) is to that peak. Everyone is measured against Big-5 players at "
                + "the same position; numbers from other leagues are first converted to Big-5 level by factors "
                + "measured from players who moved into the Big 5 (hover a league for its factors).");
        root.getChildren().addAll(new VBox(6, h, sub), form(), results);
        root.setPadding(new Insets(28, 32, 28, 32));
        results.getChildren().add(Ui.muted("Pick a player above, e.g. Kevin De Bruyne, then press Find successors."));
    }

    Node node() {
        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        sp.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        return sp;
    }

    /** Pre-selects a target (from the player page) and runs the search. */
    void setTarget(String id, String name) {
        target.setText(name);
        targetId = id;
        loadSeasons(id, true);
    }

    private Node form() {
        target.setPromptText("Player to replace (type a name)");
        target.setPrefWidth(320);
        target.textProperty().addListener((o, a, b) -> {
            if (target.isFocused()) {
                suggest(b);
            }
        });
        peakFrom.setPrefWidth(150);
        peakTo.setPrefWidth(150);
        maxAge.setPrefWidth(90);
        maxAge.disableProperty().bind(ageLimit.selectedProperty().not());
        ageLimit.setSelected(true);
        minMinutes.setPrefWidth(110);
        k.setPrefWidth(90);
        FlowPane chips = new FlowPane(6, 6);
        chips.setPrefWrapLength(1000); // otherwise the pane asks for the height of a one-chip-per-line column
        List<String> available = new ArrayList<>();
        java.util.Map<String, String> tips = new java.util.HashMap<>();
        java.util.Set<String> big5 = new java.util.HashSet<>(List.of(BIG5));
        if (app.meta() != null && !app.meta().path("leagues").isEmpty()) {
            app.meta().path("leagues").forEach(l -> {
                available.add(l.path("label").asString());
                JsonNode adj = l.path("adjustment");
                if (adj.isValueNode() && !adj.isNull()) {
                    tips.put(l.path("label").asString(), "Converted to Big-5 level: " + adj.asString());
                }
                if (l.path("big5").asBoolean()) {
                    big5.add(l.path("label").asString());
                }
            });
        } else {
            available.addAll(List.of(BIG5));
        }
        for (String l : available) {
            ToggleButton t = new ToggleButton(l);
            t.setSelected(true);
            t.getStyleClass().add(Styles.SMALL);
            if (!big5.contains(l)) {
                t.getStyleClass().add("sc-league-other");
                t.setTooltip(new javafx.scene.control.Tooltip(tips.getOrDefault(l,
                        "Outside the Big 5: no league-strength factors measured")));
            }
            leagues.add(t);
            chips.getChildren().add(t);
        }
        Button onlyBig5 = new Button("Big 5");
        Button all = new Button("All " + available.size());
        Button none = new Button("None");
        for (Button b : List.of(onlyBig5, all, none)) {
            b.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        }
        onlyBig5.setOnAction(e -> leagues.forEach(t -> t.setSelected(big5.contains(t.getText()))));
        all.setOnAction(e -> leagues.forEach(t -> t.setSelected(true)));
        none.setOnAction(e -> leagues.forEach(t -> t.setSelected(false)));
        HBox shortcuts = new HBox(4, Ui.small("Select:"), onlyBig5, all, none);
        shortcuts.setAlignment(Pos.CENTER_LEFT);
        mode.getItems().setAll("Projected next season", "Recent seasons");
        mode.getSelectionModel().selectFirst();
        run.getStyleClass().add(Styles.ACCENT);
        run.setDefaultButton(true);
        run.setOnAction(e -> search());

        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(10);
        javafx.scene.layout.ColumnConstraints labels = new javafx.scene.layout.ColumnConstraints();
        labels.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE); // row labels never shrink to "…"
        javafx.scene.layout.ColumnConstraints fields = new javafx.scene.layout.ColumnConstraints();
        fields.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        g.getColumnConstraints().addAll(labels, fields);
        int r = 0;
        g.addRow(r++, new Label("Player"), target);
        g.addRow(r++, new Label("Peak"), new HBox(8, peakFrom, new Label("to"), peakTo,
                Ui.small("Automatic = his best run of up to 3 seasons")));
        Label leaguesLabel = new Label("Leagues");
        GridPane.setValignment(leaguesLabel, javafx.geometry.VPos.TOP);
        g.addRow(r++, leaguesLabel, new VBox(6, chips, shortcuts));
        HBox numbers = new HBox(10, ageLimit, maxAge, new Label("Min minutes"), minMinutes, new Label("Show"), k);
        numbers.setAlignment(Pos.CENTER_LEFT);
        g.addRow(r++, new Label("Filters"), numbers);
        g.addRow(r++, new Label("Candidates"), new HBox(8, mode, Ui.small("Projected = each candidate's "
                + "projected next season (regressed, with aging), compared with a peak regressed the same way.")));
        g.addRow(r++, new Label("Positions"), new HBox(10, samePosition, Ui.small("Off: neighbouring positions are "
                + "included too (e.g. wingers for an attacking midfielder) and flagged in the results.")));
        g.add(run, 1, r);
        return Ui.card(g);
    }

    private void suggest(String text) {
        if (text == null || text.isBlank() || app.backend() == null) {
            picks.hide();
            return;
        }
        String q = URLEncoder.encode(text, StandardCharsets.UTF_8);
        app.call(() -> app.backend().get("/api/players/search?limit=6&q=" + q), r -> {
            if (!text.equals(target.getText())) {
                return;
            }
            picks.getItems().clear();
            for (JsonNode hit : r.path("hits")) {
                Label main = new Label(hit.path("name").asString());
                main.getStyleClass().add(Styles.TEXT_BOLD);
                Label detail = Ui.small(hit.path("position").asString("") + " · " + hit.path("club").asString("")
                        + " · " + hit.path("lastSeason").asString(""));
                CustomMenuItem item = new CustomMenuItem(new VBox(1, main, detail), true);
                item.setOnAction(e -> {
                    targetId = hit.path("id").asString();
                    target.setText(hit.path("name").asString());
                    picks.hide();
                    loadSeasons(targetId, false);
                });
                picks.getItems().add(item);
            }
            if (!picks.getItems().isEmpty() && !picks.isShowing()) {
                picks.show(target, javafx.geometry.Side.BOTTOM, 0, 4);
            }
        });
    }

    /** Fills the peak choosers with the target's seasons. */
    private void loadSeasons(String id, boolean thenSearch) {
        String path = "/api/players/" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        app.call(() -> app.backend().get(path), p -> {
            target.setText(p.path("name").asString(target.getText()));
            seasonYear.clear();
            for (JsonNode s : p.path("seasons")) {
                seasonYear.putIfAbsent(s.path("label").asString(), s.path("season").asInt());
            }
            List<String> options = new ArrayList<>();
            options.add("Automatic");
            options.addAll(seasonYear.keySet());
            peakFrom.getItems().setAll(options);
            peakTo.getItems().setAll(options);
            peakFrom.getSelectionModel().selectFirst();
            peakTo.getSelectionModel().selectFirst();
            if (thenSearch) {
                search();
            }
        });
    }

    private void search() {
        if (targetId == null) {
            results.getChildren().setAll(Ui.muted("Choose a player first: type a name and pick from the list."));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playerId", targetId);
        Integer from = seasonYear.get(peakFrom.getValue());
        Integer to = seasonYear.get(peakTo.getValue());
        if (from != null || to != null) {
            body.put("peakFrom", from != null ? from : to);
            body.put("peakTo", to != null ? to : from);
        }
        if (ageLimit.isSelected()) {
            body.put("maxAge", maxAge.getValue());
        }
        List<String> chosen = leagues.stream().filter(ToggleButton::isSelected).map(ToggleButton::getText).toList();
        if (chosen.isEmpty()) {
            results.getChildren().setAll(Ui.muted("Select at least one league."));
            return;
        }
        body.put("leagues", chosen);
        body.put("minMinutes", minMinutes.getValue());
        body.put("k", k.getValue());
        body.put("useProjection", mode.getSelectionModel().getSelectedIndex() == 0);
        body.put("samePositionOnly", samePosition.isSelected());
        run.setDisable(true);
        results.getChildren().setAll(Ui.muted("Searching…"));
        app.call(() -> {
            try {
                return app.backend().post("/api/successor", body);
            } finally {
                javafx.application.Platform.runLater(() -> run.setDisable(false));
            }
        }, this::show, err -> results.getChildren().setAll(Ui.muted("Could not search: " + err)));
    }

    private void show(JsonNode r) {
        JsonNode t = r.path("target");
        JsonNode peak = t.path("peak");
        List<String> seasons = new ArrayList<>();
        peak.path("seasons").forEach(s -> seasons.add(s.asString()));
        Label head = Ui.title(t.path("name").asString() + " at his peak", Styles.TITLE_3);
        Label line = Ui.muted(String.format(Locale.ROOT, "%s · %s · %s %s (role fit %.0f/100)", t.path("position")
                        .asString(), t.path("roleName").asString(), peak.path("automatic").asBoolean() ? "best run"
                        : "chosen seasons", String.join(", ", seasons), peak.path("rating").asDouble()));
        FlowPane profile = new FlowPane(6, 6);
        t.path("profile").properties().forEach(e -> {
            Label chip = new Label(String.format(Locale.ROOT, "%s %+.1f", short_(e.getKey()), e.getValue().asDouble()));
            chip.getStyleClass().add("sc-chip");
            profile.getChildren().add(chip);
        });
        Label profileNote = Ui.small("Profile = robust z-scores against his position's cohort in each season "
                + "(0 = median, +1 ≈ one standard deviation above).");
        VBox targetCard = Ui.card(head, line, profile, profileNote);

        VBox list = new VBox(8);
        int rank = 1;
        for (JsonNode c : r.path("candidates")) {
            list.getChildren().add(row(rank++, c));
        }
        Node spotlight = r.path("candidates").isEmpty() ? null : spotlight(t, r.path("candidates").get(0));
        if (r.path("candidates").isEmpty()) {
            list.getChildren().add(Ui.muted("Nobody matches these filters. Try a higher age limit, more leagues or fewer "
                    + "minimum minutes."));
        }
        int pool = r.path("pool").asInt();
        long work = r.path("selectionWork").asLong();
        Label how = Ui.small(String.format(Locale.ROOT, "%,d candidates compared (%,d skipped: too few stats in common). "
                        + "Top %d chosen by randomized quickselect: %,d comparisons; sorting all %,d would take about "
                        + "%,d. Seasons used: last two with at least the minimum minutes; next season = %s.", pool,
                r.path("skippedForMissingStats").asInt(), r.path("candidates").size(), work, pool,
                Math.round(pool * Math.log(Math.max(2, pool)) / Math.log(2)), r.path("nextSeason").asString()));
        results.getChildren().setAll(targetCard);
        if (spotlight != null) {
            results.getChildren().add(spotlight);
        }
        results.getChildren().addAll(Ui.title("Closest to that peak", Styles.TITLE_4), list, how);
    }

    private Node row(int rank, JsonNode c) {
        Label n = Ui.title(rank + ".", Styles.TEXT_MUTED);
        n.setMinWidth(26);
        Hyperlink name = new Hyperlink(c.path("name").asString());
        name.getStyleClass().add(Styles.TEXT_BOLD);
        name.setOnAction(e -> app.openPlayer(c.path("id").asString()));
        long value = c.path("marketValueEur").asLong();
        Label meta = Ui.small(String.format(Locale.ROOT, "%d next season · %s · %s (%s)%s · %s", c.path("ageNextSeason")
                        .asInt(), c.path("position").asString(), c.path("club").asString(), c.path("league").asString(),
                value > 0 ? String.format(Locale.ROOT, " · €%.0fm", value / 1e6) : "",
                c.path("profileKind").asString().startsWith("projected") ? c.path("profileKind").asString()
                        : String.join(" + ", toList(c.path("seasonsUsed")))));
        VBox who = new VBox(0, name, meta);
        who.setMinWidth(330);
        who.setPrefWidth(330);
        double sim = c.path("similarity").asDouble();
        Label simText = Ui.small(String.format(Locale.ROOT, "%.0f%% similar · distance %.2f on %d stats", sim,
                c.path("distance").asDouble(), c.path("statsCompared").asInt()));
        List<String> closest = toList(c.path("closest"));
        List<String> diffs = toList(c.path("differences"));
        VBox why = new VBox(2, simText, Ui.small("Closest on: " + String.join(", ", closest)),
                Ui.small(diffs.isEmpty() ? "No large differences." : "Differs: " + String.join("; ", diffs)));
        FlowPane notes = notes(c);
        if (!notes.getChildren().isEmpty()) {
            who.getChildren().add(notes);
        }
        HBox.setHgrow(why, javafx.scene.layout.Priority.ALWAYS);
        Node ring = new Charts.Ring(sim, "%", 52);
        Node avatar = Ui.avatar(c.path("name").asString(), c.path("position").asString(), 40);
        HBox h = new HBox(12, n, avatar, who, ring, why);
        h.setAlignment(Pos.CENTER_LEFT);
        h.setAlignment(Pos.TOP_LEFT);
        h.getStyleClass().add("sc-row");
        h.setPadding(new Insets(8, 10, 8, 10));
        return h;
    }

    /** Badges for a candidate from another position or from outside the Big 5. */
    private static FlowPane notes(JsonNode c) {
        FlowPane f = new FlowPane(4, 4);
        f.setPadding(new Insets(4, 0, 0, 0));
        String pos = c.path("positionNote").asString("");
        if (!pos.isBlank() && !c.path("positionNote").isNull()) {
            f.getChildren().add(Ui.note(pos));
        }
        String league = c.path("leagueNote").asString("");
        if (!league.isBlank() && !c.path("leagueNote").isNull()) {
            f.getChildren().add(Ui.note(league));
        }
        return f;
    }

    /** The best match drawn over the peak: approximate percentiles (normal CDF of the robust z-scores). */
    private Node spotlight(JsonNode target, JsonNode best) {
        List<String> axes = new ArrayList<>();
        List<Double> a = new ArrayList<>();
        List<Double> b = new ArrayList<>();
        target.path("profile").properties().forEach(e -> {
            axes.add(Charts.shortLabel(e.getKey(), short_(e.getKey())));
            a.add(Charts.percentileOfZ(Ui.num(e.getValue())));
            b.add(Charts.percentileOfZ(Ui.num(best.path("profile").path(e.getKey()))));
        });
        if (axes.size() < 3) {
            return null;
        }
        Charts.Radar radar = new Charts.Radar(axes, List.of(
                new Charts.Series(target.path("name").asString(), a.stream().mapToDouble(Double::doubleValue).toArray()),
                new Charts.Series(best.path("name").asString(), b.stream().mapToDouble(Double::doubleValue).toArray())),
                420);
        Charts.Palette pal = new Charts.Palette();
        VBox legend = new VBox(10);
        String[] names = {target.path("name").asString() + " at his peak", best.path("name").asString() + ", "
                + best.path("profileKind").asString()};
        for (int i = 0; i < 2; i++) {
            javafx.scene.layout.Region dot = new javafx.scene.layout.Region();
            dot.getStyleClass().add("sc-legend-dot");
            dot.setStyle("-fx-background-color: " + Ui.css(pal.series(i)) + ";");
            HBox item = new HBox(8, dot, new Label(names[i]));
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        Label big = Ui.title(String.format(Locale.ROOT, "%.0f%%", best.path("similarity").asDouble()), Styles.TITLE_1);
        VBox side = new VBox(10, Ui.title("Best match", Styles.TEXT_MUTED),
                Ui.title(best.path("name").asString(), Styles.TITLE_2), big, notes(best), legend,
                Ui.small("Axes: the peak's stats as approximate percentiles (normal CDF of the robust z-scores), so "
                        + "the two shapes can be read on one scale."));
        side.setMaxWidth(320);
        HBox h = new HBox(18, radar, side);
        h.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(radar, javafx.scene.layout.Priority.ALWAYS);
        return Ui.card(h);
    }

    private static List<String> toList(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.asString()));
        return out;
    }

    private static String short_(String key) {
        return switch (key) {
            case "np_goals" -> "NP goals";
            case "npxg" -> "npxG";
            case "xa" -> "xA";
            case "key_passes" -> "Key passes";
            case "xg_chain" -> "xGChain";
            case "xg_buildup" -> "xGBuildup";
            case "sca" -> "SCA";
            case "gca" -> "GCA";
            default -> Character.toUpperCase(key.charAt(0)) + key.substring(1).replace('_', ' ');
        };
    }
}

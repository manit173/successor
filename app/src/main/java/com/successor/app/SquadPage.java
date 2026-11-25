package com.successor.app;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Squad planner: several players leave; one different replacement per role, maximising the weakest match
 * ({@code POST /api/squad-plan}), shown next to what a greedy pick would have done.
 */
final class SquadPage {
    private final Ui.Services app;
    private final VBox root = new VBox(18);
    private final VBox result = new VBox(12);
    private final FlowPane chips = new FlowPane(6, 6);
    private final Map<String, String> leaving = new LinkedHashMap<>();
    private final CheckBox ageLimit = new CheckBox("Max age next season");
    private final Spinner<Integer> maxAge = new Spinner<>(16, 40, 27);
    private final Spinner<Integer> perRole = new Spinner<>(3, 50, 20);

    SquadPage(Ui.Services app) {
        this.app = app;
        PlayerPicker picker = new PlayerPicker(app, "Add a departing player", (id, name) -> {
            if (leaving.size() < 8) {
                leaving.put(id, name);
                refreshChips();
            }
        });
        picker.setOnAction(e -> picker.reset());
        ageLimit.setSelected(true);
        maxAge.disableProperty().bind(ageLimit.selectedProperty().not());
        maxAge.setPrefWidth(90);
        perRole.setPrefWidth(90);
        Button go = new Button("Plan replacements");
        go.getStyleClass().add(Styles.ACCENT);
        go.setOnAction(e -> run());
        GridPane g = new GridPane();
        g.setHgap(14);
        g.setVgap(10);
        g.addRow(0, new Label("Leaving"), new VBox(8, picker, chips));
        HBox filters = new HBox(10, ageLimit, maxAge, new Label("Candidates per role"), perRole);
        filters.setAlignment(Pos.CENTER_LEFT);
        g.addRow(1, new Label("Filters"), filters);
        g.add(go, 1, 2);
        root.getChildren().addAll(new VBox(6, Ui.title("Squad planner", Styles.TITLE_2), Ui.muted("Several players "
                + "leave at once. Each role gets a different replacement, chosen so that the weakest replacement is as "
                + "close as possible to the player he replaces (bottleneck bipartite matching via max-flow).")),
                Ui.card(g), result);
        root.setPadding(new Insets(28, 32, 28, 32));
        refreshChips();
    }

    Node node() {
        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        sp.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        return sp;
    }

    void add(String id, String name) {
        leaving.put(id, name);
        refreshChips();
    }

    /** Adds several departures and plans (used by --squad=id,id for screenshots). */
    void planFor(java.util.List<String> ids) {
        ids.forEach(id -> leaving.putIfAbsent(id, id));
        for (String id : ids) { // show names, not ids, on the chips
            app.call(() -> app.backend().get("/api/players/" + java.net.URLEncoder.encode(id,
                    java.nio.charset.StandardCharsets.UTF_8)), p -> {
                leaving.put(id, p.path("name").asString(id));
                refreshChips();
            }, err -> {
            });
        }
        refreshChips();
        run();
    }

    private void refreshChips() {
        chips.getChildren().clear();
        if (leaving.isEmpty()) {
            chips.getChildren().add(Ui.small("Nobody yet. Add up to 8 players, e.g. De Bruyne and Rodri."));
        }
        leaving.forEach((id, name) -> {
            Button chip = new Button(name + "  ✕");
            chip.getStyleClass().addAll(Styles.SMALL, Styles.BUTTON_OUTLINED);
            chip.setOnAction(e -> {
                leaving.remove(id);
                refreshChips();
            });
            chips.getChildren().add(chip);
        });
    }

    private void run() {
        if (leaving.isEmpty()) {
            result.getChildren().setAll(Ui.muted("Add at least one departing player."));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("departures", new ArrayList<>(leaving.keySet()));
        if (ageLimit.isSelected()) {
            body.put("maxAge", maxAge.getValue());
        }
        body.put("candidatesPerRole", perRole.getValue());
        result.getChildren().setAll(Ui.muted("Planning…"));
        app.call(() -> app.backend().post("/api/squad-plan", body), this::show,
                err -> result.getChildren().setAll(Ui.muted("Could not plan: " + err)));
    }

    private void show(JsonNode r) {
        VBox plan = new VBox(6, Ui.title("Best plan: weakest replacement " + pct(r.path("weakest")),
                Styles.TITLE_4));
        for (JsonNode a : r.path("matching")) {
            plan.getChildren().add(line(a));
        }
        VBox greedy = new VBox(6, Ui.title("Greedy pick for comparison: weakest " + pct(r.path("greedyWeakest"))
                + (r.path("greedyFilled").asInt() < r.path("matching").size()
                ? " · " + (r.path("matching").size() - r.path("greedyFilled").asInt()) + " role(s) left empty" : ""),
                Styles.TEXT_BOLD));
        for (JsonNode a : r.path("greedy")) {
            greedy.getChildren().add(line(a));
        }
        List<String> unfilled = new ArrayList<>();
        r.path("unfilled").forEach(x -> unfilled.add(x.asString()));
        Label note = Ui.small(String.format(Locale.ROOT, "%s %,d candidates, %,d edges; matching work %,d. "
                        + "Replacements are judged on their projected %s.", r.path("note").asString(),
                r.path("candidates").asInt(), r.path("edges").asInt(), r.path("matchingWork").asLong(),
                r.path("nextSeason").asString()));
        result.getChildren().setAll(Ui.card(plan), Ui.card(greedy), note);
        if (!unfilled.isEmpty()) {
            result.getChildren().add(1, Ui.muted("No suitable replacement under these filters for: "
                    + String.join(", ", unfilled) + ". Try a higher age limit or more candidates per role."));
        }
    }

    private Node line(JsonNode a) {
        Label who = new Label(a.path("departureName").asString() + "  (" + a.path("position").asString() + ", "
                + a.path("role").asString() + ")");
        who.setMinWidth(300);
        who.setPrefWidth(300);
        Node right;
        if (a.path("candidateId").isNull() || a.path("candidateId").isMissingNode()) {
            right = Ui.small("no replacement");
        } else {
            javafx.scene.control.Hyperlink link = new javafx.scene.control.Hyperlink(a.path("candidateName")
                    .asString());
            link.setOnAction(e -> app.openPlayer(a.path("candidateId").asString()));
            Label meta = Ui.small(a.path("age").asInt() + " · " + a.path("candidatePosition").asString("") + " · "
                    + a.path("club").asString() + " (" + a.path("league").asString() + ")");
            link.setMinWidth(Region.USE_PREF_SIZE);
            HBox line = new HBox(8, Ui.bar("", Ui.num(a.path("similarity")), pct(a.path("similarity")), 0, 140), link,
                    meta);
            line.setAlignment(Pos.CENTER_LEFT);
            FlowPane badges = new FlowPane(4, 4);
            for (String k : new String[]{"positionNote", "leagueNote"}) {
                if (!a.path(k).isNull() && !a.path(k).asString("").isBlank()) {
                    badges.getChildren().add(Ui.note(a.path(k).asString()));
                }
            }
            right = badges.getChildren().isEmpty() ? line : new VBox(4, line, badges);
        }
        HBox h = new HBox(12, who, Ui.small("→"), right);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static String pct(JsonNode v) {
        double x = Ui.num(v);
        return Double.isNaN(x) ? "–" : String.format(Locale.ROOT, "%.0f%% similar", x);
    }
}

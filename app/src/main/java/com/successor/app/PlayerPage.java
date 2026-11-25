package com.successor.app;

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Player profile: header, role radar, career trend with projection, next-season table and every season. */
final class PlayerPage {
    private PlayerPage() {
    }

    private record Col(String title, String key, int decimals) {
    }

    private static final List<Col> COLUMNS = List.of(new Col("Min", "minutes", 0), new Col("G", "goals", 0),
            new Col("A", "assists", 0), new Col("xG", "xg", 1), new Col("xA", "xa", 1),
            new Col("Shots", "shots", 0), new Col("KP", "key_passes", 0), new Col("xGChain", "xg_chain", 1),
            new Col("PrgP", "prog_passes", 0), new Col("PrgC", "prog_carries", 0), new Col("SCA", "sca", 0),
            new Col("Tkl", "tackles", 0), new Col("Int", "interceptions", 0), new Col("Recov", "recoveries", 0));

    static Node build(JsonNode p, JsonNode analysis, JsonNode projection, Ui.Services app) {
        VBox box = new VBox(18, header(p, analysis, app));
        HBox top = new HBox(18, roleSection(analysis), trendSection(p, projection));
        HBox.setHgrow(top.getChildren().get(1), Priority.ALWAYS);
        box.getChildren().addAll(top, projectionSection(projection), seasonsTable(p));
        box.setPadding(new Insets(26, 32, 26, 32));
        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.getStyleClass().add(Tweaks.EDGE_TO_EDGE);
        return sp;
    }

    // ------------------------------------------------------------------------------------------------ header

    private static Node header(JsonNode p, JsonNode analysis, Ui.Services app) {
        String name = p.path("name").asString();
        JsonNode seasons = p.path("seasons");
        JsonNode last = seasons.isEmpty() ? null : seasons.get(seasons.size() - 1);
        String position = p.path("position").asString(last == null ? "" : last.path("position").asString(""));
        Node avatar = Ui.avatar(name, position, 64);

        Label title = Ui.title(name, Styles.TITLE_1);
        FlowPane chips = new FlowPane(6, 6);
        if (!p.path("subPosition").asString("").isBlank()) {
            chips.getChildren().add(Ui.chip(p.path("subPosition").asString(), "sc-chip-accent"));
        }
        String role = latestRole(analysis);
        if (role != null) {
            chips.getChildren().add(Ui.chip(role, "sc-chip-accent"));
        }
        if (last != null) {
            chips.getChildren().add(Ui.chip(last.path("club").asString() + " · " + last.path("league").asString(),
                    null));
        }
        String born = p.path("birthDate").asString("");
        if (!born.isBlank()) {
            int age = Period.between(LocalDate.parse(born), LocalDate.now()).getYears();
            chips.getChildren().add(Ui.chip(age + " years", null));
        }
        if (!p.path("nation").asString("").isBlank()) {
            chips.getChildren().add(Ui.chip(p.path("nation").asString(), null));
        }
        long value = p.path("marketValueEur").asLong();
        if (value > 0) {
            chips.getChildren().add(Ui.chip(String.format(Locale.ROOT, "€%.1fm", value / 1e6), "sc-chip-green"));
        }
        VBox who = new VBox(8, title, chips);
        List<String> aliases = new ArrayList<>();
        p.path("aliases").forEach(a -> aliases.add(a.asString()));
        if (!aliases.isEmpty()) {
            who.getChildren().add(Ui.small("Also listed as " + String.join(", ", aliases)));
        }

        Button successor = new Button("Find a successor");
        successor.getStyleClass().add(Styles.ACCENT);
        successor.setOnAction(e -> app.findSuccessor(p.path("id").asString(), name));
        Button compare = new Button("Compare…");
        compare.setOnAction(e -> app.compareWith(p.path("id").asString(), name));
        Button squad = new Button("Plan his replacement");
        squad.setOnAction(e -> app.addToSquad(p.path("id").asString(), name));
        HBox actions = new HBox(8, compare, squad, successor);
        actions.setAlignment(Pos.TOP_RIGHT);

        HBox h = new HBox(18, avatar, who, Ui.grow(), actions);
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    private static String latestRole(JsonNode analysis) {
        JsonNode best = bestSeason(analysis);
        if (best == null) {
            return null;
        }
        for (JsonNode f : best.path("roles")) {
            if (f.path("fit").isNumber()) {
                return f.path("name").asString();
            }
        }
        return null;
    }

    /** The most completely measured eligible season (latest among equals). */
    private static JsonNode bestSeason(JsonNode analysis) {
        JsonNode best = null;
        if (analysis == null) {
            return null;
        }
        for (JsonNode a : analysis) {
            if (a.path("eligible").asBoolean() && !a.path("stats").isEmpty()
                    && (best == null || a.path("stats").size() >= best.path("stats").size())) {
                best = a;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ role radar

    private static Node roleSection(JsonNode analysis) {
        List<JsonNode> eligible = new ArrayList<>();
        if (analysis != null) {
            analysis.forEach(a -> {
                if (a.path("eligible").asBoolean() && !a.path("stats").isEmpty()) {
                    eligible.add(a);
                }
            });
        }
        VBox card;
        if (eligible.isEmpty()) {
            card = Ui.card(Ui.title("Role and percentiles", Styles.TITLE_4), Ui.muted("No season with at least 900 "
                    + "minutes and measured stats, so there are no role or percentile figures."));
        } else {
            ComboBox<String> season = new ComboBox<>();
            eligible.reversed().forEach(a -> season.getItems().add(a.path("label").asString() + " · "
                    + a.path("club").asString()));
            VBox body = new VBox(10);
            season.setOnAction(e -> {
                int i = eligible.size() - 1 - season.getSelectionModel().getSelectedIndex();
                body.getChildren().setAll(seasonDetail(eligible.get(i)));
            });
            JsonNode best = bestSeason(analysis);
            int bi = eligible.indexOf(best);
            season.getSelectionModel().select(eligible.size() - 1 - bi);
            body.getChildren().setAll(seasonDetail(best));
            HBox top = new HBox(10, Ui.title("Role and percentiles", Styles.TITLE_4), Ui.grow(), season);
            top.setAlignment(Pos.CENTER_LEFT);
            card = Ui.card(top, body);
        }
        card.setPrefWidth(470);
        card.setMinWidth(470);
        return card;
    }

    private static List<Node> seasonDetail(JsonNode a) {
        List<Node> out = new ArrayList<>();
        List<String> fits = new ArrayList<>();
        JsonNode best = null;
        for (JsonNode f : a.path("roles")) {
            if (f.path("fit").isNumber()) {
                if (best == null) {
                    best = f;
                }
                fits.add(String.format(Locale.ROOT, "%s %.0f", f.path("name").asString(), f.path("fit").asDouble()));
            }
        }
        HBox role = new HBox(12);
        role.setAlignment(Pos.CENTER_LEFT);
        if (best != null) {
            Label explain = Ui.small("Role fit 0–100 = mean percentile on the role's stats vs " + a.path("position")
                    .asString() + "s that season. " + String.join(" · ", fits));
            VBox text = new VBox(2, Ui.title(best.path("name").asString(), Styles.TEXT_BOLD), explain);
            HBox.setHgrow(text, Priority.ALWAYS);
            role.getChildren().addAll(new Charts.Ring(best.path("fit").asDouble(), "fit", 56), text);
        } else {
            role.getChildren().add(Ui.title("Role: not enough measured stats", Styles.TEXT_BOLD));
        }
        out.add(role);
        if (a.path("stats").size() < 4) {
            Label warn = Ui.small("Only " + a.path("stats").size() + " stats were measured this season, so this "
                    + "picture is rough. Pick a season with fuller data above.");
            warn.getStyleClass().add(Styles.WARNING);
            out.add(warn);
        }
        List<String> axes = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        int cohort = 0;
        for (JsonNode st : a.path("stats")) {
            axes.add(Charts.shortLabel(st.path("stat").asString(), st.path("label").asString()));
            values.add(st.path("percentile").asDouble());
            cohort = Math.max(cohort, st.path("cohortSize").asInt());
        }
        if (axes.size() >= 3) {
            out.add(new Charts.Radar(axes, List.of(new Charts.Series(a.path("label").asString(),
                    values.stream().mapToDouble(Double::doubleValue).toArray())), 400));
        }
        String league = a.path("league").asString("");
        boolean big5 = List.of("Premier League", "La Liga", "Bundesliga", "Serie A", "Ligue 1").contains(league);
        out.add(Ui.small(String.format(Locale.ROOT, "Percentiles against %d Big-5 %ss with 900+ minutes in %s (rings "
                        + "at 25, 50, 75, 100).%s Medians and quartiles by randomized quickselect.", cohort,
                a.path("position").asString(), a.path("label").asString(), big5 || league.isEmpty() ? ""
                        : " His " + league + " numbers are first converted to Big-5 level by the measured "
                        + "league-strength factors; the per-90 values shown are his own.")));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ trend

    private static final Map<String, String> TREND_STATS = new LinkedHashMap<>();

    static {
        TREND_STATS.put("xa", "xA (expected assists)");
        TREND_STATS.put("key_passes", "Key passes");
        TREND_STATS.put("npxg", "Non-penalty xG");
        TREND_STATS.put("goals", "Goals");
        TREND_STATS.put("assists", "Assists");
        TREND_STATS.put("shots", "Shots");
        TREND_STATS.put("sca", "Shot-creating actions");
        TREND_STATS.put("prog_passes", "Progressive passes");
        TREND_STATS.put("prog_carries", "Progressive carries");
        TREND_STATS.put("tackles", "Tackles");
        TREND_STATS.put("interceptions", "Interceptions");
        TREND_STATS.put("recoveries", "Ball recoveries");
        TREND_STATS.put("minutes", "Minutes");
    }

    private static Node trendSection(JsonNode p, JsonNode projection) {
        ComboBox<String> stat = new ComboBox<>();
        Map<String, String> byLabel = new LinkedHashMap<>();
        TREND_STATS.forEach((k, label) -> {
            if (hasStat(p, k)) {
                byLabel.put(label, k);
            }
        });
        stat.getItems().setAll(byLabel.keySet());
        StackPane chart = new StackPane();
        chart.setMinHeight(250);
        VBox.setVgrow(chart, Priority.ALWAYS);
        Label caption = Ui.small("");
        stat.setOnAction(e -> {
            String key = byLabel.get(stat.getValue());
            chart.getChildren().setAll(trend(p, projection, key));
            caption.setText(key.equals("minutes") ? "League minutes per season (all clubs combined)."
                    : "Per 90 minutes, each season with 450+ minutes. Hollow dot: projected " + (projection == null
                    ? "next season" : projection.path("season").asString("next season")) + " with its 80% range.");
        });
        if (!byLabel.isEmpty()) {
            stat.getSelectionModel().selectFirst();
            stat.getOnAction().handle(null);
        }
        HBox top = new HBox(10, Ui.title("Career", Styles.TITLE_4), Ui.grow(), stat);
        top.setAlignment(Pos.CENTER_LEFT);
        return Ui.card(top, chart, caption);
    }

    private static boolean hasStat(JsonNode p, String key) {
        for (JsonNode s : p.path("seasons")) {
            if (!s.path("stats").path(key).isMissingNode()) {
                return true;
            }
        }
        return false;
    }

    private static Node trend(JsonNode p, JsonNode projection, String key) {
        // combine the stints of one season, weighted by minutes
        Map<String, double[]> bySeason = new LinkedHashMap<>(); // label → {value sum, minutes, measured minutes}
        for (JsonNode s : p.path("seasons")) {
            double[] acc = bySeason.computeIfAbsent(s.path("label").asString(), k -> new double[3]);
            double min = Ui.num(s.path("stats").path("minutes"));
            JsonNode v = s.path("stats").path(key);
            acc[1] += Double.isNaN(min) ? 0 : min;
            if (!v.isMissingNode() && !Double.isNaN(min)) {
                acc[0] += v.asDouble();
                acc[2] += min;
            }
        }
        List<Charts.Point> pts = new ArrayList<>();
        for (Map.Entry<String, double[]> e : bySeason.entrySet()) {
            double[] a = e.getValue();
            double v = key.equals("minutes") ? a[1] : a[2] >= 450 ? a[0] * 90 / a[2] : Double.NaN;
            pts.add(new Charts.Point(e.getKey(), v));
        }
        String projLabel = null;
        double proj = Double.NaN;
        double lo = Double.NaN;
        double hi = Double.NaN;
        if (projection != null && !projection.has("error") && !key.equals("minutes")) {
            for (JsonNode s : projection.path("stats")) {
                if (s.path("stat").asString().equals(key)) {
                    projLabel = projection.path("season").asString();
                    proj = Ui.num(s.path("per90"));
                    lo = Ui.num(s.path("low"));
                    hi = Ui.num(s.path("high"));
                }
            }
        }
        return new Charts.Trend(pts, projLabel, proj, lo, hi, key.equals("minutes") ? "minutes" : "per 90");
    }

    // ------------------------------------------------------------------------------------------------ projection

    private static Node projectionSection(JsonNode pr) {
        if (pr == null || pr.isMissingNode() || pr.has("error")) {
            return Ui.card(Ui.title("Next season", Styles.TITLE_4), Ui.muted(pr != null && pr.has("error")
                    ? pr.path("error").asString() : "No projection available."));
        }
        GridPane g = new GridPane();
        g.setHgap(24);
        g.setVgap(7);
        String[] head = {"Stat (per 90)", "Last measured", "Projected " + pr.path("season").asString(), "80% range",
                "Change"};
        for (int c = 0; c < head.length; c++) {
            g.add(Ui.small(head[c]), c, 0);
        }
        int row = 1;
        for (JsonNode s : pr.path("stats")) {
            double last = Ui.num(s.path("lastPer90"));
            double proj = Ui.num(s.path("per90"));
            double lo = Ui.num(s.path("low"));
            double hi = Ui.num(s.path("high"));
            Label value = new Label(String.format(Locale.ROOT, "%.2f", proj));
            value.getStyleClass().add(Styles.TEXT_BOLD);
            Label change = Ui.small("");
            if (!Double.isNaN(last) && last > 0) {
                double pct = 100 * (proj - last) / last;
                change.setText(String.format(Locale.ROOT, "%+.0f%%", pct));
                change.getStyleClass().add(pct >= 0 ? Styles.SUCCESS : Styles.DANGER);
            }
            g.addRow(row++, new Label(s.path("label").asString()), Ui.small(Double.isNaN(last) ? "–"
                            : String.format(Locale.ROOT, "%.2f (%s)", last, s.path("lastSeason").asString())), value,
                    Ui.small(Double.isNaN(lo) ? "no range (one season measured)"
                            : String.format(Locale.ROOT, "%.2f – %.2f", lo, hi)), change);
        }
        List<String> based = new ArrayList<>();
        pr.path("basedOn").forEach(x -> based.add(x.asString()));
        Label meta = Ui.small(String.format(Locale.ROOT, "As a %s, age %d next season · from %s · %s · %d comparable "
                        + "players for the range.", pr.path("position").asString(), pr.path("age").asInt(),
                String.join(", ", based), pr.path("method").asString().toLowerCase(Locale.ROOT),
                pr.path("comparables").asInt()));
        return Ui.card(Ui.title("Next season · " + pr.path("season").asString(), Styles.TITLE_4), g, meta,
                Ui.small(pr.path("note").asString()));
    }

    // ------------------------------------------------------------------------------------------------ seasons

    private static Node seasonsTable(JsonNode p) {
        TableView<JsonNode> table = new TableView<>();
        table.getStyleClass().addAll(Styles.STRIPED, Tweaks.EDGE_TO_EDGE, Styles.DENSE);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY); // scroll sideways rather than truncate
        table.getColumns().add(text("Season", s -> s.path("label").asString(), 88));
        table.getColumns().add(text("Club", s -> s.path("club").asString(), 175));
        table.getColumns().add(text("League", s -> s.path("league").asString(), 128));
        table.getColumns().add(text("Pos", s -> s.path("position").asString(""), 58));
        table.getColumns().add(text("Age", s -> s.path("age").asInt() < 0 ? "" : s.path("age").asString(), 58));
        for (Col c : COLUMNS) {
            TableColumn<JsonNode, String> col = text(c.title(), s -> {
                JsonNode v = s.path("stats").path(c.key());
                return v.isMissingNode() ? "–" : String.format(Locale.ROOT, "%,." + c.decimals() + "f", v.asDouble());
            }, c.key().equals("minutes") ? 78 : 66);
            col.setStyle("-fx-alignment: CENTER-RIGHT;");
            table.getColumns().add(col);
        }
        table.getColumns().add(text("Sources", s -> {
            List<String> src = new ArrayList<>();
            s.path("sources").forEach(x -> src.add(x.asString().toLowerCase(Locale.ROOT).replace('_', ' ')));
            return String.join(", ", src);
        }, 230));
        List<JsonNode> rows = new ArrayList<>();
        p.path("seasons").forEach(rows::add);
        table.setItems(FXCollections.observableArrayList(rows.reversed()));
        table.setPrefHeight(Math.min(560, 40 + 34 * rows.size()));
        table.setMinHeight(Math.min(300, 40 + 34 * rows.size()));
        return Ui.card(Ui.title("Seasons", Styles.TITLE_4), table, Ui.small("– = not measured by the sources for that "
                + "season (never treated as 0). Full FBref profiles exist for 2021/22–2024/25; Understat covers "
                + "2014/15–2023/24; 2025/26 has basic stats only."));
    }

    private static TableColumn<JsonNode, String> text(String title, java.util.function.Function<JsonNode, String> f,
                                                      double width) {
        TableColumn<JsonNode, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        c.setPrefWidth(width);
        c.setMinWidth(width);
        c.setSortable(false);
        return c;
    }
}

package com.successor.app;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import tools.jackson.databind.JsonNode;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small shared UI pieces and the app services pages need. */
final class Ui {
    private Ui() {
    }

    /** What a page needs from the app: API calls off the UI thread and navigation. */
    interface Services {
        Backend backend();

        void call(Supplier<JsonNode> request, Consumer<JsonNode> onSuccess);

        /** Like {@link #call(Supplier, Consumer)}, but the page shows the server's explanation itself. */
        void call(Supplier<JsonNode> request, Consumer<JsonNode> onSuccess, Consumer<String> onError);

        void openPlayer(String id);

        void findSuccessor(String id, String name);

        void compareWith(String id, String name);

        void addToSquad(String id, String name);

        /** {@code GET /api/meta} once connected (null before), e.g. the leagues in the data. */
        JsonNode meta();
    }

    /** Amber badge for "different position" / "outside the Big 5" notes. */
    static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().addAll("sc-pill", "sc-chip-warn");
        l.setWrapText(true);
        return l;
    }

    /** A JSON number, or NaN for null / missing / the string "NaN" (how the API writes values it could not measure). */
    static double num(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return Double.NaN;
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(n.asString());
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** A round avatar with the player's initials, coloured by position (no photos: they are not ours to use). */
    static Node avatar(String name, String position, double size) {
        String[] parts = name.trim().split("\\s+");
        String initials = parts.length == 0 || parts[0].isEmpty() ? "?"
                : (parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : ""))
                .toUpperCase(java.util.Locale.ROOT);
        javafx.scene.shape.Circle c = new javafx.scene.shape.Circle(size / 2);
        javafx.scene.paint.Color base = switch (position == null ? "" : position) {
            case "GK" -> javafx.scene.paint.Color.web("#ff9f0a");
            case "CB", "FB" -> javafx.scene.paint.Color.web("#0a84ff");
            case "DM", "CM" -> javafx.scene.paint.Color.web("#30b158");
            case "AM", "W" -> javafx.scene.paint.Color.web("#bf5af2");
            case "ST" -> javafx.scene.paint.Color.web("#ff453a");
            default -> javafx.scene.paint.Color.web("#8e8e93");
        };
        c.setFill(new javafx.scene.paint.LinearGradient(0, 0, 0, 1, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, base.brighter()), new javafx.scene.paint.Stop(1, base.darker())));
        Label l = new Label(initials);
        l.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.36) + "px;");
        javafx.scene.layout.StackPane sp = new javafx.scene.layout.StackPane(c, l);
        sp.setMinSize(size, size);
        sp.setMaxSize(size, size);
        sp.getStyleClass().add("sc-avatar");
        return sp;
    }

    /** A small rounded label; style classes: sc-chip-accent, sc-chip-green, or null for neutral. */
    static Label chip(String text, String style) {
        Label l = new Label(text);
        l.getStyleClass().add("sc-pill");
        if (style != null) {
            l.getStyleClass().add(style);
        }
        return l;
    }

    static String css(javafx.scene.paint.Color c) {
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.3f)", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), c.getOpacity());
    }

    static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sc-muted");
        l.setWrapText(true);
        return l;
    }

    static Label small(String text) {
        Label l = muted(text);
        l.getStyleClass().add(Styles.TEXT_SMALL);
        return l;
    }

    static Label title(String text, String style) {
        Label l = new Label(text);
        l.getStyleClass().add(style);
        return l;
    }

    static VBox card(Node... children) {
        VBox v = new VBox(10, children);
        v.getStyleClass().add("sc-card");
        v.setPadding(new Insets(16));
        return v;
    }

    static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** A labelled horizontal bar for a 0–100 value (percentile, similarity). */
    static HBox bar(String label, double value, String right, double labelWidth) {
        return bar(label, value, right, labelWidth, 220);
    }

    static HBox bar(String label, double value, String right, double labelWidth, double barWidth) {
        Label name = new Label(label);
        name.setMinWidth(labelWidth);
        name.setPrefWidth(labelWidth);
        ProgressBar pb = new ProgressBar(Math.max(0, Math.min(1, value / 100)));
        pb.setPrefWidth(barWidth);
        pb.setMinWidth(barWidth);
        pb.getStyleClass().add(value >= 80 ? Styles.SUCCESS : value >= 50 ? "sc-mid" : Styles.WARNING);
        Label r = new Label(right);
        r.getStyleClass().addAll("sc-muted", Styles.TEXT_SMALL);
        r.setMinWidth(Region.USE_PREF_SIZE);
        HBox h = new HBox(10, name, pb, r);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    static String ordinal(double pct) {
        int n = (int) Math.round(pct);
        String suffix = (n % 100 >= 11 && n % 100 <= 13) ? "th"
                : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }
}

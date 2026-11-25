package com.successor.app;

import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Charts drawn on a canvas that resizes with its pane and follows the light/dark appearance and the macOS accent
 * colour: a radar ("pizza") chart of percentiles, a career trend line with a projected season and range, and a
 * similarity ring.
 */
final class Charts {
    private Charts() {
    }

    /** Colours for the current appearance. */
    static final class Palette {
        final boolean dark;
        final Color accent;
        final Color text;
        final Color muted;
        final Color grid;
        final Color second;
        final Color third;

        Palette() {
            dark = SuccessorApp.darkTheme();
            Color a = Platform.getPreferences().getAccentColor();
            accent = a == null ? Color.web("#0a84ff") : a;
            text = dark ? Color.web("#f2f2f7") : Color.web("#1c1c1e");
            muted = dark ? Color.web("#98989f") : Color.web("#6e6e73");
            grid = dark ? Color.web("#ffffff", 0.12) : Color.web("#000000", 0.10);
            second = Color.web("#ff9f0a");
            third = Color.web("#30d158");
        }

        Color series(int i) {
            return switch (i) {
                case 0 -> accent;
                case 1 -> second;
                default -> third;
            };
        }
    }

    /** A canvas that fills its pane and redraws on resize. */
    abstract static class CanvasPane extends Pane {
        final Canvas canvas = new Canvas();

        CanvasPane(double prefW, double prefH) {
            getChildren().add(canvas);
            setPrefSize(prefW, prefH);
            setMinSize(120, 100);
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();
            if (canvas.getWidth() != w || canvas.getHeight() != h) {
                canvas.setWidth(w);
                canvas.setHeight(h);
            }
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, w, h);
            draw(g, w, h, new Palette());
        }

        abstract void draw(GraphicsContext g, double w, double h, Palette p);

        void redraw() {
            requestLayout();
            layoutChildren();
        }
    }

    // ------------------------------------------------------------------------------------------------ radar

    /** One series on a radar: a name and values 0–100 per axis (NaN = not measured). */
    record Series(String name, double[] values) {
    }

    static final class Radar extends CanvasPane {
        private final List<String> axes;
        private final List<Series> series;

        Radar(List<String> axes, List<Series> series, double size) {
            super(size + 60, size);
            // drop axes nobody has a value for: an empty spoke would read as a zero
            List<String> keptAxes = new ArrayList<>();
            List<List<Double>> kept = new ArrayList<>();
            series.forEach(s -> kept.add(new ArrayList<>()));
            for (int i = 0; i < axes.size(); i++) {
                final int axis = i;
                if (series.stream().anyMatch(s -> !Double.isNaN(s.values()[axis]))) {
                    keptAxes.add(axes.get(i));
                    for (int s = 0; s < series.size(); s++) {
                        kept.get(s).add(series.get(s).values()[i]);
                    }
                }
            }
            this.axes = List.copyOf(keptAxes);
            List<Series> out = new ArrayList<>();
            for (int s = 0; s < series.size(); s++) {
                out.add(new Series(series.get(s).name(), kept.get(s).stream().mapToDouble(Double::doubleValue)
                        .toArray()));
            }
            this.series = List.copyOf(out);
        }

        @Override
        void draw(GraphicsContext g, double w, double h, Palette p) {
            int n = axes.size();
            if (n < 3) {
                return;
            }
            double cx = w / 2;
            double cy = h / 2 + 4;
            // room for the widest label on both sides (measured), so long names are never clipped
            Font labelFont = Font.font("System", FontWeight.MEDIUM, n > 14 ? 10 : 11);
            double widest = 0;
            for (int i = 0; i < n; i++) {
                javafx.scene.text.Text t = new javafx.scene.text.Text(axes.get(i) + (series.size() == 1 ? "  100" : ""));
                t.setFont(labelFont);
                widest = Math.max(widest, t.getLayoutBounds().getWidth());
            }
            double r = Math.min((w - 2 * (widest + 24)) / 2, (h - 64) / 2);
            if (r < 20) {
                return;
            }
            // rings at 25/50/75/100
            g.setLineWidth(1);
            for (int ring = 1; ring <= 4; ring++) {
                double rr = r * ring / 4;
                g.setStroke(p.grid);
                g.beginPath();
                for (int i = 0; i <= n; i++) {
                    double a = angle(i % n, n);
                    double x = cx + rr * Math.cos(a);
                    double y = cy + rr * Math.sin(a);
                    if (i == 0) {
                        g.moveTo(x, y);
                    } else {
                        g.lineTo(x, y);
                    }
                }
                g.stroke();
            }
            for (int i = 0; i < n; i++) {
                double a = angle(i, n);
                g.setStroke(p.grid);
                g.strokeLine(cx, cy, cx + r * Math.cos(a), cy + r * Math.sin(a));
            }
            // series: filled polygons, the first one on top
            for (int s = series.size() - 1; s >= 0; s--) {
                Series se = series.get(s);
                Color c = p.series(s);
                g.beginPath();
                boolean first = true;
                for (int i = 0; i <= n; i++) {
                    double v = se.values()[i % n];
                    double rr = r * (Double.isNaN(v) ? 0 : Math.max(2, v)) / 100;
                    double a = angle(i % n, n);
                    double x = cx + rr * Math.cos(a);
                    double y = cy + rr * Math.sin(a);
                    if (first) {
                        g.moveTo(x, y);
                        first = false;
                    } else {
                        g.lineTo(x, y);
                    }
                }
                g.closePath();
                g.setFill(c.deriveColor(0, 1, 1, series.size() > 1 ? 0.18 : 0.28));
                g.fill();
                g.setStroke(c);
                g.setLineWidth(2.2);
                g.stroke();
                for (int i = 0; i < n; i++) {
                    double v = se.values()[i];
                    if (Double.isNaN(v)) {
                        continue;
                    }
                    double rr = r * Math.max(2, v) / 100;
                    double a = angle(i, n);
                    g.setFill(c);
                    g.fillOval(cx + rr * Math.cos(a) - 3.5, cy + rr * Math.sin(a) - 3.5, 7, 7);
                }
            }
            // labels
            g.setFont(labelFont);
            g.setTextBaseline(VPos.CENTER);
            for (int i = 0; i < n; i++) {
                double a = angle(i, n);
                double lx = cx + (r + 14) * Math.cos(a);
                double ly = cy + (r + 14) * Math.sin(a);
                double cos = Math.cos(a);
                // centre only the axes at the very top and bottom; the rest grow away from each other
                g.setTextAlign(Math.abs(cos) < 0.08 ? TextAlignment.CENTER : cos > 0 ? TextAlignment.LEFT
                        : TextAlignment.RIGHT);
                final int axis = i;
                boolean measured = series.stream().anyMatch(se -> !Double.isNaN(se.values()[axis]));
                g.setFill(measured ? p.text : p.muted);
                String text = axes.get(i);
                if (series.size() == 1 && measured) {
                    text += "  " + String.format(Locale.ROOT, "%.0f", series.getFirst().values()[i]);
                }
                g.fillText(text, lx, ly);
            }
        }

        private static double angle(int i, int n) {
            return -Math.PI / 2 + 2 * Math.PI * i / n;
        }
    }

    // ------------------------------------------------------------------------------------------------ trend

    /** A point of a career line: season label, value (NaN = gap). */
    record Point(String label, double value) {
    }

    /** Career line with an optional projected point and 80% range at the end. */
    static final class Trend extends CanvasPane {
        private final List<Point> points;
        private final String projectedLabel;
        private final double projected;
        private final double low;
        private final double high;
        private final String unit;

        Trend(List<Point> points, String projectedLabel, double projected, double low, double high, String unit) {
            super(560, 220);
            this.points = List.copyOf(points);
            this.projectedLabel = projectedLabel;
            this.projected = projected;
            this.low = low;
            this.high = high;
            this.unit = unit;
        }

        @Override
        void draw(GraphicsContext g, double w, double h, Palette p) {
            List<Point> all = new ArrayList<>(points);
            boolean hasProj = projectedLabel != null && !Double.isNaN(projected);
            if (hasProj) {
                all.add(new Point(projectedLabel, projected));
            }
            if (all.isEmpty()) {
                return;
            }
            double max = 0;
            for (Point pt : all) {
                if (!Double.isNaN(pt.value())) {
                    max = Math.max(max, pt.value());
                }
            }
            if (hasProj && !Double.isNaN(high)) {
                max = Math.max(max, high);
            }
            max = max <= 0 ? 1 : niceCeil(max * 1.1);
            double left = 46;
            double right = w - 16;
            double top = 14;
            double bottom = h - 30;
            int n = all.size();
            double step = n == 1 ? 0 : (right - left) / (n - 1);
            // grid + y labels
            g.setFont(Font.font("System", 10));
            g.setTextBaseline(VPos.CENTER);
            g.setTextAlign(TextAlignment.RIGHT);
            for (int i = 0; i <= 4; i++) {
                double v = max * i / 4;
                double y = bottom - (bottom - top) * i / 4;
                g.setStroke(p.grid);
                g.setLineWidth(1);
                g.strokeLine(left, y, right, y);
                g.setFill(p.muted);
                g.fillText(fmt(v), left - 8, y);
            }
            // x labels
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.TOP);
            int every = Math.max(1, (int) Math.ceil(n / ((right - left) / 58.0)));
            for (int i = 0; i < n; i++) {
                if (i % every == 0 || i == n - 1) {
                    g.setFill(hasProj && i == n - 1 ? p.accent : p.muted);
                    g.fillText(shortSeason(all.get(i).label()), left + i * step, bottom + 8);
                }
            }
            // area + line through measured seasons
            int last = points.size() - 1;
            g.setLineWidth(2.4);
            g.setLineCap(StrokeLineCap.ROUND);
            g.setStroke(p.accent);
            g.beginPath();
            boolean pen = false;
            double lastX = Double.NaN;
            double lastY = Double.NaN;
            for (int i = 0; i <= last; i++) {
                double v = points.get(i).value();
                if (Double.isNaN(v)) {
                    pen = false;
                    continue;
                }
                double x = left + i * step;
                double y = bottom - (bottom - top) * v / max;
                if (!pen) {
                    g.moveTo(x, y);
                    pen = true;
                } else {
                    g.lineTo(x, y);
                }
                lastX = x;
                lastY = y;
            }
            g.stroke();
            for (int i = 0; i <= last; i++) {
                double v = points.get(i).value();
                if (!Double.isNaN(v)) {
                    double x = left + i * step;
                    double y = bottom - (bottom - top) * v / max;
                    g.setFill(p.accent);
                    g.fillOval(x - 4, y - 4, 8, 8);
                }
            }
            // projection: dashed link, range whisker, hollow dot
            if (hasProj) {
                double x = left + (n - 1) * step;
                double y = bottom - (bottom - top) * projected / max;
                if (!Double.isNaN(lastX)) {
                    g.setLineDashes(5, 5);
                    g.setStroke(p.accent.deriveColor(0, 1, 1, 0.7));
                    g.setLineWidth(2);
                    g.strokeLine(lastX, lastY, x, y);
                    g.setLineDashes((double[]) null);
                }
                if (!Double.isNaN(low) && !Double.isNaN(high)) {
                    double yl = bottom - (bottom - top) * low / max;
                    double yh = bottom - (bottom - top) * high / max;
                    g.setFill(p.accent.deriveColor(0, 1, 1, 0.16));
                    g.fillRoundRect(x - 11, yh, 22, yl - yh, 10, 10);
                    g.setStroke(p.accent.deriveColor(0, 1, 1, 0.55));
                    g.setLineWidth(1.5);
                    g.strokeRoundRect(x - 11, yh, 22, yl - yh, 10, 10);
                }
                g.setFill(p.dark ? Color.web("#1c1c1e") : Color.WHITE);
                g.fillOval(x - 6, y - 6, 12, 12);
                g.setStroke(p.accent);
                g.setLineWidth(2.5);
                g.strokeOval(x - 6, y - 6, 12, 12);
            }
            g.setFill(p.muted);
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.TOP);
            g.fillText(unit, left, 0);
        }

        private static String shortSeason(String label) {
            return label.length() == 7 ? "'" + label.substring(2) : label; // 2019/20 → '19/20
        }

        private static String fmt(double v) {
            return v >= 10 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.2f", v);
        }

        private static double niceCeil(double v) {
            double mag = Math.pow(10, Math.floor(Math.log10(v)));
            for (double m : new double[]{1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10}) {
                if (m * mag >= v) {
                    return m * mag;
                }
            }
            return 10 * mag;
        }
    }

    // ------------------------------------------------------------------------------------------------ ring

    /** A circular gauge for a 0–100 value with the number in the middle. */
    static final class Ring extends CanvasPane {
        private final double value;
        private final String caption;

        Ring(double value, String caption, double size) {
            super(size, size);
            this.value = value;
            this.caption = caption;
            setMinSize(size, size);
            setMaxSize(size, size);
        }

        @Override
        void draw(GraphicsContext g, double w, double h, Palette p) {
            double s = Math.min(w, h);
            double stroke = Math.max(4, s * 0.1);
            double d = s - stroke - 2;
            double x = (w - d) / 2;
            double y = (h - d) / 2;
            g.setLineWidth(stroke);
            g.setLineCap(StrokeLineCap.ROUND);
            g.setStroke(p.grid);
            g.strokeArc(x, y, d, d, 0, 360, ArcType.OPEN);
            if (!Double.isNaN(value)) {
                Color c = value >= 70 ? p.third : value >= 45 ? p.accent : p.second;
                g.setStroke(c);
                g.strokeArc(x, y, d, d, 90, -3.6 * Math.max(1, Math.min(100, value)), ArcType.OPEN);
            }
            g.setFill(p.text);
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.setFont(Font.font("System", FontWeight.BOLD, s * 0.27));
            g.fillText(Double.isNaN(value) ? "–" : String.format(Locale.ROOT, "%.0f", value), w / 2,
                    h / 2 - (caption == null ? 0 : s * 0.06));
            if (caption != null) {
                g.setFill(p.muted);
                g.setFont(Font.font("System", s * 0.12));
                g.fillText(caption, w / 2, h / 2 + s * 0.17);
            }
        }
    }

    /** Standard normal CDF ×100: turns a robust z-score into an approximate percentile for display. */
    static double percentileOfZ(double z) {
        if (Double.isNaN(z)) {
            return Double.NaN;
        }
        // Abramowitz–Stegun 7.1.26 approximation of erf
        double x = Math.abs(z) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * x);
        double erf = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-x * x);
        double cdf = 0.5 * (1 + Math.signum(z) * erf);
        return 100 * cdf;
    }

    /** Short axis labels for the radar. */
    static String shortLabel(String key, String label) {
        return switch (key) {
            case "xa" -> "xA";
            case "npxg" -> "npxG";
            case "np_goals" -> "NP goals";
            case "key_passes" -> "Key passes";
            case "xg_chain" -> "xGChain";
            case "xg_buildup" -> "xGBuildup";
            case "sca" -> "Shot-creating";
            case "gca" -> "Goal-creating";
            case "prog_passes" -> "Prog. passes";
            case "prog_carries" -> "Prog. carries";
            case "prog_receptions" -> "Prog. received";
            case "passes_into_box" -> "Into the box";
            case "passes_final_third" -> "Final third";
            case "crosses_into_box" -> "Crosses to box";
            case "carries_into_box" -> "Carries to box";
            case "touches_att_box" -> "Box touches";
            case "take_ons" -> "Take-ons";
            case "passes_completed" -> "Passes";
            case "passes_attempted" -> "Pass volume";
            case "tackles_won" -> "Tackles won";
            case "aerials_won" -> "Aerials";
            case "goals_against" -> "Goals against";
            case "clean_sheets" -> "Clean sheets";
            default -> label.length() > 14 ? label.substring(0, 13) + "…" : label;
        };
    }
}

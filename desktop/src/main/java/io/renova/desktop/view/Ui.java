package io.renova.desktop.view;

import atlantafx.base.theme.Styles;
import io.renova.desktop.service.Phases;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.util.Duration;

import java.util.List;

/** Small building blocks shared by the views. Their look is in app.css. */
public final class Ui {

    public enum Tone { GOOD, BAD, WARN, INFO, MUTED }

    /** One part of a whole, or one bar: a label, its value and the series colour (1 to 5) that identifies it. */
    public record Segment(String code, String label, int value, int series) {
    }

    private Ui() {
    }

    public static Label label(String text, String... styles) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styles);
        label.setWrapText(true);
        return label;
    }

    public static Label badge(String text, Tone tone) {
        Label badge = new Label(text);
        badge.getStyleClass().addAll("badge", "badge-" + tone.name().toLowerCase());
        badge.setMinWidth(Region.USE_PREF_SIZE);
        return badge;
    }

    /** A badge led by an icon, so the state is never told by colour alone. */
    public static Label badge(String text, Tone tone, String icon) {
        Label badge = badge(text, tone);
        badge.setGraphic(Icons.of(icon, 12, switch (tone) {
            case GOOD -> "good";
            case BAD -> "bad";
            case WARN -> "warn";
            case INFO -> "accent";
            case MUTED -> "muted";
        }));
        return badge;
    }

    public static Region dot(Tone tone) {
        Region dot = new Region();
        dot.getStyleClass().addAll("dot", "dot-" + tone.name().toLowerCase());
        return dot;
    }

    /** A page title, an optional line under it, and actions on the right. */
    public static Node header(String title, String subtitle, Node... actions) {
        return header(null, title, subtitle, actions);
    }

    /** As {@link #header(String, String, Node...)}, with an icon tile for pages about one thing. */
    public static Node header(String icon, String title, String subtitle, Node... actions) {
        VBox text = new VBox(3, label(title, "page-title"));
        if (subtitle != null) {
            text.getChildren().add(label(subtitle, Styles.TEXT_MUTED));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox row = new HBox(10);
        if (icon != null) {
            StackPane tile = new StackPane(Icons.of(icon, 20, "accent"));
            tile.getStyleClass().add("icon-tile");
            HBox.setMargin(tile, new Insets(2, 4, 0, 0));
            row.getChildren().add(tile);
        }
        row.getChildren().add(text);
        row.setAlignment(Pos.TOP_LEFT);
        for (Node action : actions) {
            if (action instanceof Region r) {
                // Buttons keep their full labels.
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        if (actions.length <= 2) {
            row.getChildren().addAll(actions);
            return row;
        }
        // Many actions get a row of their own, so the title and the path beside them are not squeezed into wrapping.
        HBox bar = new HBox(8, actions);
        bar.setAlignment(Pos.CENTER_LEFT);
        return new VBox(14, row, bar);
    }

    public static VBox stat(String label, String value, String hint) {
        return stat(label, label(value, "stat-value"), hint);
    }

    /** A headline figure. {@code value} may be a badge when the figure is a state rather than a number. */
    public static VBox stat(String label, Node value, String hint) {
        VBox card = new VBox(label(label, Styles.TEXT_MUTED, Styles.TEXT_SMALL), value);
        if (hint != null) {
            card.getChildren().add(label(hint, Styles.TEXT_MUTED, Styles.TEXT_SMALL));
        }
        card.getStyleClass().add("stat-card");
        card.setMinWidth(170);
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /** A titled card. */
    public static VBox section(String title, String description, Node body) {
        VBox head = new VBox(2, label(title, "panel-title"));
        if (description != null) {
            head.getChildren().add(label(description, Styles.TEXT_MUTED, Styles.TEXT_SMALL));
        }
        head.getStyleClass().add("panel-head");
        VBox content = new VBox(body);
        content.getStyleClass().add("panel-body");
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);
        VBox panel = new VBox(head, content);
        panel.getStyleClass().add("panel");
        return panel;
    }

    /** What a list shows when there is nothing in it yet. */
    public static VBox empty(String icon, String title, String text, Node... actions) {
        StackPane tile = new StackPane(Icons.of(icon, 20));
        tile.getStyleClass().add("icon-tile");
        Label description = label(text, Styles.TEXT_MUTED, Styles.TEXT_SMALL);
        description.setMaxWidth(440);
        description.setAlignment(Pos.CENTER);
        description.setStyle("-fx-text-alignment: center;");
        VBox box = new VBox(tile, label(title, Styles.TEXT_BOLD), description);
        if (actions.length > 0) {
            HBox row = new HBox(8, actions);
            row.setAlignment(Pos.CENTER);
            row.setPadding(new Insets(8, 0, 0, 0));
            box.getChildren().add(row);
        }
        box.getStyleClass().add("empty");
        return box;
    }

    public static VBox page(Node... children) {
        VBox page = new VBox(18, children);
        page.setPadding(new Insets(26, 32, 32, 32));
        page.setFillWidth(true);
        return page;
    }

    public static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** A ring showing one share of a whole, with the figure in the middle. */
    public static Node gauge(double share, String caption) {
        double radius = 56;
        Arc track = new Arc(0, 0, radius, radius, 0, 360);
        track.setType(ArcType.OPEN);
        track.getStyleClass().add("gauge-track");
        // Clockwise from twelve o'clock; a full ring is drawn a little short so its round ends do not overlap.
        Arc value = new Arc(0, 0, radius, radius, 90, -Math.max(0, Math.min(1, share)) * 359.9);
        value.setType(ArcType.OPEN);
        value.getStyleClass().add("gauge-value");
        VBox figure = new VBox(label(percent(share), "gauge-figure"), label(caption, Styles.TEXT_MUTED, Styles.TEXT_SMALL));
        figure.setAlignment(Pos.CENTER);
        StackPane gauge = new StackPane(track, value, figure);
        gauge.setMinSize(132, 132);
        gauge.setMaxSize(132, 132);
        gauge.setAccessibleText(caption + ": " + percent(share));
        return gauge;
    }

    /** One bar split into the parts of a whole, with a legend that names each part and its value. */
    public static Node stackedBar(List<Segment> segments) {
        int total = segments.stream().mapToInt(Segment::value).sum();
        GridPane bar = new GridPane();
        bar.setHgap(2);
        HBox legend = new HBox(18);
        int column = 0;
        for (Segment s : segments) {
            if (s.value() > 0) {
                Region part = new Region();
                part.getStyleClass().add("series-" + s.series());
                part.setMinHeight(12);
                part.setStyle("-fx-background-radius: 3;");
                Tooltip.install(part, new Tooltip(s.label() + ": " + s.value() + " (" + percent((double) s.value() / total) + ")"));
                ColumnConstraints width = new ColumnConstraints();
                width.setPercentWidth(100.0 * s.value() / total);
                bar.getColumnConstraints().add(width);
                bar.add(part, column++, 0);
            }
            Region swatch = new Region();
            swatch.getStyleClass().addAll("swatch", "series-" + s.series());
            HBox item = new HBox(7, swatch, label(s.label(), Styles.TEXT_MUTED, Styles.TEXT_SMALL), label(String.valueOf(s.value()), Styles.TEXT_BOLD, Styles.TEXT_SMALL));
            item.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(item);
        }
        if (total == 0) {
            Region none = new Region();
            none.getStyleClass().add("bar-track");
            none.setMinHeight(12);
            return new VBox(10, none, legend);
        }
        return new VBox(10, bar, legend);
    }

    /** Horizontal bars from one baseline, the largest full width, each with its value at the tip. */
    public static Node bars(List<Segment> rows) {
        int max = Math.max(1, rows.stream().mapToInt(Segment::value).max().orElse(1));
        GridPane grid = new GridPane(12, 10);
        ColumnConstraints code = new ColumnConstraints();
        ColumnConstraints names = new ColumnConstraints();
        ColumnConstraints bar = new ColumnConstraints();
        bar.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(code, names, bar);
        int row = 0;
        for (Segment s : rows) {
            Region fill = new Region();
            fill.getStyleClass().add("series-" + s.series());
            fill.setMinHeight(14);
            fill.setStyle("-fx-background-radius: 0 4 4 0;");
            GridPane line = new GridPane();
            ColumnConstraints filled = new ColumnConstraints();
            // The widest bar stops short of the edge so its value fits beside it.
            filled.setPercentWidth(92.0 * s.value() / max);
            line.getColumnConstraints().add(filled);
            line.add(fill, 0, 0);
            Label value = label(String.valueOf(s.value()), Styles.TEXT_BOLD);
            GridPane.setMargin(value, new Insets(0, 0, 0, 8));
            line.add(value, 1, 0);
            Tooltip.install(fill, new Tooltip(s.label() + ": " + s.value()));
            Label name = new Label(s.label());
            name.getStyleClass().add(Styles.TEXT_MUTED);
            name.setMinWidth(Region.USE_PREF_SIZE);
            grid.addRow(row++, label(s.code(), "code-chip"), name, line);
        }
        return grid;
    }

    /** The phases of a migration as a row of steps joined by a line. */
    public static Node pipeline(List<Phases.Phase> phases) {
        GridPane grid = new GridPane();
        for (int i = 0; i < phases.size(); i++) {
            Phases.Phase p = phases.get(i);
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / phases.size());
            grid.getColumnConstraints().add(column);

            Label dot = new Label();
            dot.getStyleClass().addAll("phase-dot", "phase-" + p.state().name().toLowerCase());
            switch (p.state()) {
                case DONE -> dot.setGraphic(Icons.of(Icons.CHECK, 13, "on-colour"));
                case FAILED -> dot.setGraphic(Icons.of(Icons.CROSS, 13, "on-colour"));
                case WARN -> dot.setGraphic(Icons.of(Icons.ALERT, 13, "on-colour"));
                case SKIPPED -> dot.setGraphic(Icons.of(Icons.MINUS, 12));
                case CURRENT -> {
                    Region pulse = new Region();
                    pulse.getStyleClass().add("phase-pulse");
                    FadeTransition fade = new FadeTransition(Duration.millis(800), pulse);
                    fade.setFromValue(1);
                    fade.setToValue(0.3);
                    fade.setAutoReverse(true);
                    fade.setCycleCount(FadeTransition.INDEFINITE);
                    fade.play();
                    dot.setGraphic(pulse);
                }
                case PENDING -> dot.setText(String.valueOf(i + 1));
            }
            // The line to this step runs from the previous dot; it is coloured once this step has started.
            Region before = new Region();
            before.getStyleClass().add("phase-line");
            Region after = new Region();
            after.getStyleClass().add("phase-line");
            boolean reached = p.state() != Phases.State.PENDING && p.state() != Phases.State.SKIPPED;
            if (reached) {
                before.getStyleClass().add("reached");
            }
            if (i + 1 < phases.size() && phases.get(i + 1).state() != Phases.State.PENDING && phases.get(i + 1).state() != Phases.State.SKIPPED) {
                after.getStyleClass().add("reached");
            }
            before.setVisible(i > 0);
            after.setVisible(i + 1 < phases.size());
            HBox.setHgrow(before, Priority.ALWAYS);
            HBox.setHgrow(after, Priority.ALWAYS);
            HBox line = new HBox(before, dot, after);
            line.setAlignment(Pos.CENTER);

            String state = switch (p.state()) {
                case DONE -> "done";
                case CURRENT -> "in progress";
                case PENDING -> "not started";
                case SKIPPED -> "not run";
                case FAILED -> "failed";
                case WARN -> "needs attention";
            };
            Label name = label(p.label(), Styles.TEXT_BOLD);
            if (!reached) {
                name.getStyleClass().add(Styles.TEXT_MUTED);
            }
            VBox step = new VBox(6, line, name, label(state, Styles.TEXT_MUTED, Styles.TEXT_SMALL));
            step.setAlignment(Pos.TOP_CENTER);
            step.setAccessibleText(p.label() + ": " + state);
            grid.add(step, i, 0);
        }
        return grid;
    }

    /** A list of progress lines styled as a terminal, with the lines that matter picked out. */
    public static ListView<String> console(javafx.collections.ObservableList<String> lines) {
        ListView<String> log = new ListView<>(lines);
        log.getStyleClass().addAll("log", "console", Styles.DENSE);
        log.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String line, boolean empty) {
                super.updateItem(line, empty);
                getStyleClass().removeAll("strong", "trouble", "fine");
                setText(empty ? null : line);
                if (!empty && line != null) {
                    if (line.matches("(?i)^(Error|Cancelled).*|.*\\b(fails?|failed|differs)\\b.*")) {
                        getStyleClass().add("trouble");
                    } else if (line.startsWith("Finished")) {
                        getStyleClass().add("fine");
                    } else if (line.matches("^(Stage |Verifying|Checking|Plan:).*")) {
                        getStyleClass().add("strong");
                    }
                }
            }
        });
        return log;
    }

    public static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }

    public static String tokens(long n) {
        return n >= 1000 ? String.format("%.1fK", n / 1000.0) : String.valueOf(n);
    }
}

package io.renova.desktop.view;

import atlantafx.base.controls.Card;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Small building blocks shared by the views. */
public final class Ui {

    public enum Tone { GOOD, BAD, WARN, INFO, MUTED }

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
        return badge;
    }

    /** A page title, an optional line under it, and actions on the right. */
    public static Node header(String title, String subtitle, Node... actions) {
        VBox text = new VBox(4, label(title, Styles.TITLE_2));
        if (subtitle != null) {
            text.getChildren().add(label(subtitle, Styles.TEXT_MUTED));
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMinWidth(0);
        HBox row = new HBox(8, text);
        for (Node action : actions) {
            if (action instanceof Region r) {
                // Buttons keep their full labels; a long subtitle wraps instead.
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
            row.getChildren().add(action);
        }
        row.setAlignment(Pos.BOTTOM_LEFT);
        return row;
    }

    public static Card stat(String label, String value, String hint) {
        Card card = new Card();
        VBox body = new VBox(2, label(label, Styles.TEXT_MUTED, Styles.TEXT_SMALL), label(value, Styles.TITLE_3));
        if (hint != null) {
            body.getChildren().add(label(hint, Styles.TEXT_MUTED, Styles.TEXT_SMALL));
        }
        card.setBody(body);
        card.setMinWidth(160);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    public static Card section(String title, String description, Node body) {
        Card card = new Card();
        VBox head = new VBox(2, label(title, Styles.TITLE_4));
        if (description != null) {
            head.getChildren().add(label(description, Styles.TEXT_MUTED, Styles.TEXT_SMALL));
        }
        card.setHeader(head);
        card.setBody(body);
        return card;
    }

    public static VBox page(Node... children) {
        VBox page = new VBox(16, children);
        page.setPadding(new Insets(24, 32, 32, 32));
        page.setFillWidth(true);
        return page;
    }

    public static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    public static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }

    public static String tokens(long n) {
        return n >= 1000 ? String.format("%.1fK", n / 1000.0) : String.valueOf(n);
    }
}
